// 琉光的 Vulkan 后端（试验）：Java 这边（G、Vk）照旧按 GL 的写法画，只是不交给 GL，而是把每次绘制记成一串整数（用哪段程序、什么状态、
// uniform 在这一帧数据里的偏移、哪几张贴图、哪个网格），一帧一次交过来（nFrame），这里一口气录成一个命令缓冲提交、显示。
// 资源（程序、网格、贴图、离屏缓冲）按编号建、按编号删；删的东西可能还在路上的帧里用着，等那几帧做完才真的释放。
// 着色器是打包时从 Java 的原文转好编好的 SPIR-V（vk/shaders.mjs）：uniform 分顶点、片元两个块（集 0 的两个动态 UBO），
// sampler 最多 4 个（集 1），顶点着色器最后乘推送常量里的 2×2 矩阵（画到屏幕上时上下翻、按预旋转转过去）。
// 只在一个线程（VkSceneView 的画画线程）里调用。
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "Liuguang", __VA_ARGS__)

namespace {

const int FR = 2;          // 同时在路上的帧
const int BLOCK = 1024;    // 一个 uniform 块最多多少字节（UBO 描述符的范围）
// 状态：低两位是混合（0 不混、1 预乘盖上 ONE / 1-SA、2 挖掉 ZERO / 1-SA、3 整体减 ONE / ONE 反向减），再往上是深度测试、写深度、剔除背面
enum { ST_BLEND = 3, ST_DTEST = 4, ST_DWRITE = 8, ST_CULL = 16 };
// 命令（整数个数）：PASS 7、DRAW 14、CLEAR 6、COPY 3
enum { CMD_PASS = 1, CMD_DRAW = 2, CMD_CLEAR = 3, CMD_COPY = 4 };
int cmdLen(int op) { return op == CMD_PASS ? 7 : op == CMD_DRAW ? 14 : op == CMD_CLEAR ? 6 : 3; }

struct Buf { VkBuffer b = VK_NULL_HANDLE; VkDeviceMemory m = VK_NULL_HANDLE; void* p = nullptr; VkDeviceSize size = 0; };
struct Img {
    VkImage img = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE; VkImageView view = VK_NULL_HANDLE;
    uint32_t w = 0, h = 0, mips = 1; bool alive = false;
    VkFormat fmt = VK_FORMAT_R8G8B8A8_UNORM;
    // 离屏缓冲：颜色就是上面这张（多重采样时是解析目标），另有多重采样的颜色、深度、帧缓冲
    bool target = false, depth = false; int samples = 1, cls = -1;
    VkImage ms = VK_NULL_HANDLE; VkDeviceMemory msMem = VK_NULL_HANDLE; VkImageView msView = VK_NULL_HANDLE;
    VkImage dImg = VK_NULL_HANDLE; VkDeviceMemory dMem = VK_NULL_HANDLE; VkImageView dView = VK_NULL_HANDLE;
    VkFramebuffer fb = VK_NULL_HANDLE;
};
struct Mesh { Buf vb, ib; int layout = 0; uint32_t count = 0; bool indexed = false, alive = false; };
struct Prog { VkShaderModule vs = VK_NULL_HANDLE, fs = VK_NULL_HANDLE; bool alive = false; };
struct Layout { std::vector<int> sizes; int stride = 0; };
/** 一类渲染流程（同类的流程、管线互相兼容）：颜色格式、采样数、有没有深度、是不是屏幕 */
struct Cls {
    VkFormat fmt; int samples; bool depth, screen;
    VkRenderPass rp[3][2] = {}; // [颜色：0 接着画 1 清掉 2 不管][深度：0 不管 1 清掉]
};
struct Ctx {
    AAssetManager* am = nullptr;
    std::string cacheDir;
    ANativeWindow* win = nullptr;
    VkInstance inst = VK_NULL_HANDLE;
    VkSurfaceKHR surf = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memp{};
    VkDevice dev = VK_NULL_HANDLE;
    uint32_t qf = 0;
    VkQueue q = VK_NULL_HANDLE;
    VkFormat depthFmt = VK_FORMAT_D16_UNORM, scFmt = VK_FORMAT_R8G8B8A8_UNORM;
    uint32_t uboAlign = 256;
    int samples = 1; // 屏幕的采样数
    // 交换链
    VkSwapchainKHR sc = VK_NULL_HANDLE;
    VkExtent2D ext{0, 0}, view{0, 0};
    VkSurfaceTransformFlagBitsKHR pre = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    float rot[4] = {1, 0, 0, -1}; // 画到屏幕上的 2×2 矩阵（先上下翻，再按预旋转转）
    std::vector<VkImage> scImgs;
    std::vector<VkImageView> scViews;
    std::vector<VkFramebuffer> scFbs;
    std::vector<VkSemaphore> done;
    Img scMs, scDepth; // 屏幕的多重采样颜色、深度
    int scCls = -1;
    // 共用
    VkDescriptorSetLayout dsl0 = VK_NULL_HANDLE, dsl1 = VK_NULL_HANDLE;
    VkPipelineLayout pl = VK_NULL_HANDLE;
    VkDescriptorPool pool0 = VK_NULL_HANDLE, pool1 = VK_NULL_HANDLE;
    VkDescriptorSet set0 = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkPipelineCache pcache = VK_NULL_HANDLE;
    Buf ring; VkDeviceSize seg = 0; // uniform 数据：每个在路上的帧一段
    Buf iring; VkDeviceSize iseg = 0; // 实例化绘制的每实例数据（雨珠）：同上
    VkCommandPool cpool = VK_NULL_HANDLE;
    VkCommandBuffer cmd[FR] = {}, up[FR] = {};
    VkSemaphore acq[FR] = {};
    VkFence fence[FR] = {};
    bool upOpen = false, waited = false;
    uint64_t submitted = 0; // 交出去的帧数（下一帧的序号）
    // 资源表（编号 = 下标；贴图 0 是一张 1×1 的黑图，代替"没绑贴图"）
    std::vector<Img> imgs;
    std::vector<Mesh> meshes;
    std::vector<Prog> progs;
    std::vector<Layout> layouts;
    std::vector<Cls> classes;
    std::unordered_map<uint64_t, VkPipeline> pipes;
    std::unordered_map<uint64_t, VkDescriptorSet> sets;
    struct Dead { uint64_t at; int kind; Buf buf; Img img; VkDescriptorSet set; VkShaderModule mod; };
    std::vector<Dead> dead;
    int newPipes = 0;
};

uint32_t memType(Ctx& c, uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < c.memp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (c.memp.memoryTypes[i].propertyFlags & want) == want) return i;
    return UINT32_MAX;
}

bool bufMake(Ctx& c, VkDeviceSize size, VkBufferUsageFlags use, Buf& out) {
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size; bi.usage = use; bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(c.dev, &bi, nullptr, &out.b) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(c.dev, out.b, &mr);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = memType(c, mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX || vkAllocateMemory(c.dev, &ai, nullptr, &out.m) != VK_SUCCESS) { vkDestroyBuffer(c.dev, out.b, nullptr); out = Buf(); return false; }
    vkBindBufferMemory(c.dev, out.b, out.m, 0);
    vkMapMemory(c.dev, out.m, 0, VK_WHOLE_SIZE, 0, &out.p);
    out.size = size;
    return true;
}
void bufFree(Ctx& c, Buf& b) {
    if (b.b) vkDestroyBuffer(c.dev, b.b, nullptr);
    if (b.m) vkFreeMemory(c.dev, b.m, nullptr);
    b = Buf();
}

bool imgMake(Ctx& c, uint32_t w, uint32_t h, uint32_t mips, VkFormat fmt, VkImageUsageFlags use, int samples, bool lazy, VkImageAspectFlags aspect,
             const VkComponentMapping* swz, VkImage& img, VkDeviceMemory& mem, VkImageView& view) {
    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ii.imageType = VK_IMAGE_TYPE_2D; ii.format = fmt; ii.extent = {w, h, 1}; ii.mipLevels = mips; ii.arrayLayers = 1;
    ii.samples = (VkSampleCountFlagBits) samples; ii.tiling = VK_IMAGE_TILING_OPTIMAL; ii.usage = use;
    ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE; ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(c.dev, &ii, nullptr, &img) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(c.dev, img, &mr);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = lazy ? memType(c, mr.memoryTypeBits, VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT) : UINT32_MAX;
    if (ai.memoryTypeIndex == UINT32_MAX) ai.memoryTypeIndex = memType(c, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) ai.memoryTypeIndex = memType(c, mr.memoryTypeBits, 0);
    if (vkAllocateMemory(c.dev, &ai, nullptr, &mem) != VK_SUCCESS) { vkDestroyImage(c.dev, img, nullptr); img = VK_NULL_HANDLE; return false; }
    vkBindImageMemory(c.dev, img, mem, 0);
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = img; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = fmt;
    if (swz) vi.components = *swz;
    vi.subresourceRange = {aspect, 0, mips, 0, 1};
    vkCreateImageView(c.dev, &vi, nullptr, &view);
    return true;
}

void imgFree(Ctx& c, Img& m) {
    if (m.fb) vkDestroyFramebuffer(c.dev, m.fb, nullptr);
    VkImageView v[3] = {m.view, m.msView, m.dView};
    VkImage i[3] = {m.img, m.ms, m.dImg};
    VkDeviceMemory d[3] = {m.mem, m.msMem, m.dMem};
    for (int k = 0; k < 3; k++) {
        if (v[k]) vkDestroyImageView(c.dev, v[k], nullptr);
        if (i[k]) vkDestroyImage(c.dev, i[k], nullptr);
        if (d[k]) vkFreeMemory(c.dev, d[k], nullptr);
    }
    m = Img();
}

/** 删的东西等这一帧（和之前交出去的帧）都做完再释放 */
void later(Ctx& c, int kind, const Buf* b, const Img* m, VkDescriptorSet s, VkShaderModule mod) {
    Ctx::Dead d{c.submitted, kind, b ? *b : Buf(), m ? *m : Img(), s, mod};
    c.dead.push_back(d);
}
void reap(Ctx& c, bool all) {
    size_t k = 0;
    for (size_t i = 0; i < c.dead.size(); i++) {
        Ctx::Dead& d = c.dead[i];
        if (all || d.at + FR <= c.submitted) {
            if (d.kind == 0) bufFree(c, d.buf);
            else if (d.kind == 1) imgFree(c, d.img);
            else if (d.kind == 2) vkFreeDescriptorSets(c.dev, c.pool1, 1, &d.set);
            else if (d.kind == 3) vkDestroyShaderModule(c.dev, d.mod, nullptr);
        } else c.dead[k++] = d;
    }
    c.dead.resize(k);
}

/** 这一帧的位置：等它上一轮做完（同一个位置的命令缓冲、上传缓冲才能再用），顺便释放到期的东西 */
void waitSlot(Ctx& c) {
    if (c.waited) return;
    int f = (int) (c.submitted % FR);
    vkWaitForFences(c.dev, 1, &c.fence[f], VK_TRUE, UINT64_MAX);
    c.waited = true;
    reap(c, false);
}

/** 上传用的命令缓冲（这一帧第一次要传东西时开） */
VkCommandBuffer upCmd(Ctx& c) {
    waitSlot(c);
    int f = (int) (c.submitted % FR);
    if (!c.upOpen) {
        vkResetCommandBuffer(c.up[f], 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(c.up[f], &bi);
        c.upOpen = true;
    }
    return c.up[f];
}

void barrier(VkCommandBuffer cb, VkImage img, VkImageAspectFlags aspect, uint32_t mip0, uint32_t mips, VkImageLayout from, VkImageLayout to,
             VkAccessFlags sa, VkAccessFlags da, VkPipelineStageFlags ss, VkPipelineStageFlags ds) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.oldLayout = from; b.newLayout = to; b.srcAccessMask = sa; b.dstAccessMask = da;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img; b.subresourceRange = {aspect, mip0, mips, 0, 1};
    vkCmdPipelineBarrier(cb, ss, ds, 0, 0, nullptr, 0, nullptr, 1, &b);
}

// ---------------- 渲染流程 ----------------
VkRenderPass passOf(Ctx& c, int ci, int load, int dclear) {
    Cls& k = c.classes[ci];
    if (!k.depth) dclear = 0;
    if (k.samples > 1 && load == 0) load = 2;       // 多重采样的颜色不存回内存，没法接着画
    if (k.screen && load == 0) load = 2;             // 屏幕取来的图里是什么不确定
    VkRenderPass& rp = k.rp[load][dclear];
    if (rp) return rp;
    std::vector<VkAttachmentDescription> at;
    VkAttachmentReference cref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL}, dref{}, rref{};
    VkImageLayout fin = k.screen ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkAttachmentDescription a{};
    a.format = k.fmt; a.samples = (VkSampleCountFlagBits) k.samples;
    a.loadOp = load == 0 ? VK_ATTACHMENT_LOAD_OP_LOAD : load == 1 ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    a.storeOp = k.samples > 1 ? VK_ATTACHMENT_STORE_OP_DONT_CARE : VK_ATTACHMENT_STORE_OP_STORE;
    a.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE; a.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    a.initialLayout = load == 0 ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;
    a.finalLayout = k.samples > 1 ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : fin;
    at.push_back(a);
    if (k.depth) {
        VkAttachmentDescription d{};
        d.format = c.depthFmt; d.samples = (VkSampleCountFlagBits) k.samples;
        d.loadOp = dclear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        d.storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        d.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE; d.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        d.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED; d.finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        dref = {(uint32_t) at.size(), VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
        at.push_back(d);
    }
    if (k.samples > 1) {
        VkAttachmentDescription r{};
        r.format = k.fmt; r.samples = VK_SAMPLE_COUNT_1_BIT;
        r.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE; r.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        r.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE; r.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        r.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED; r.finalLayout = fin;
        rref = {(uint32_t) at.size(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        at.push_back(r);
    }
    VkSubpassDescription sp{};
    sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sp.colorAttachmentCount = 1; sp.pColorAttachments = &cref;
    if (k.depth) sp.pDepthStencilAttachment = &dref;
    if (k.samples > 1) sp.pResolveAttachments = &rref;
    VkSubpassDependency dep[2] = {};
    dep[0].srcSubpass = VK_SUBPASS_EXTERNAL; dep[0].dstSubpass = 0;
    dep[0].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    dep[0].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    dep[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    dep[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    dep[1].srcSubpass = 0; dep[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    dep[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    dep[1].dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    dep[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    VkRenderPassCreateInfo ri{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    ri.attachmentCount = (uint32_t) at.size(); ri.pAttachments = at.data();
    ri.subpassCount = 1; ri.pSubpasses = &sp;
    ri.dependencyCount = 2; ri.pDependencies = dep;
    if (vkCreateRenderPass(c.dev, &ri, nullptr, &rp) != VK_SUCCESS) { LOGW("Vulkan：建渲染流程失败"); rp = VK_NULL_HANDLE; }
    return rp;
}

int clsOf(Ctx& c, VkFormat fmt, int samples, bool depth, bool screen) {
    for (size_t i = 0; i < c.classes.size(); i++) {
        Cls& k = c.classes[i];
        if (k.fmt == fmt && k.samples == samples && k.depth == depth && k.screen == screen) return (int) i;
    }
    Cls k{fmt, samples, depth, screen};
    c.classes.push_back(k);
    return (int) c.classes.size() - 1;
}

// ---------------- 管线 ----------------
/** inst：1 是每实例两个 vec4（接在每顶点的属性后面，绑定 1） */
VkPipeline pipeOf(Ctx& c, int prog, int state, int ci, int layout, bool points, int inst) {
    uint64_t key = (uint64_t) prog | ((uint64_t) state << 16) | ((uint64_t) ci << 24) | ((uint64_t) layout << 32) | ((uint64_t) points << 48) | ((uint64_t) inst << 49);
    auto it = c.pipes.find(key);
    if (it != c.pipes.end()) return it->second;
    Prog& p = c.progs[prog];
    Cls& k = c.classes[ci];
    Layout& L = c.layouts[layout];
    VkPipelineShaderStageCreateInfo st[2] = {{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}, {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}};
    st[0].stage = VK_SHADER_STAGE_VERTEX_BIT; st[0].module = p.vs; st[0].pName = "main";
    st[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; st[1].module = p.fs; st[1].pName = "main";
    VkVertexInputBindingDescription vb[2] = {{0, (uint32_t) L.stride * 4, VK_VERTEX_INPUT_RATE_VERTEX}, {1, 32, VK_VERTEX_INPUT_RATE_INSTANCE}};
    std::vector<VkVertexInputAttributeDescription> va;
    static const VkFormat F[] = {VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32B32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT};
    int off = 0;
    for (size_t i = 0; i < L.sizes.size(); i++) { va.push_back({(uint32_t) i, 0, F[L.sizes[i]], (uint32_t) off * 4}); off += L.sizes[i]; }
    if (inst) for (uint32_t k = 0; k < 2; k++) va.push_back({(uint32_t) L.sizes.size() + k, 1, VK_FORMAT_R32G32B32A32_SFLOAT, k * 16});
    VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    vi.vertexBindingDescriptionCount = inst ? 2 : 1; vi.pVertexBindingDescriptions = vb;
    vi.vertexAttributeDescriptionCount = (uint32_t) va.size(); vi.pVertexAttributeDescriptions = va.data();
    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = points ? VK_PRIMITIVE_TOPOLOGY_POINT_LIST : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1; vp.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = (state & ST_CULL) ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE;
    // GL 逆时针是正面；离屏的图和 GL 一样按行从下往上存（不翻），Vulkan 算面积的符号和 GL 相反，所以是顺时针；屏幕那遍在着色器里上下翻过，又反回来
    rs.frontFace = k.screen ? VK_FRONT_FACE_COUNTER_CLOCKWISE : VK_FRONT_FACE_CLOCKWISE;
    rs.lineWidth = 1;
    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = (VkSampleCountFlagBits) k.samples;
    VkPipelineDepthStencilStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
    ds.depthTestEnable = k.depth && (state & ST_DTEST);
    ds.depthWriteEnable = k.depth && (state & ST_DTEST) && (state & ST_DWRITE); // GL 关着深度测试时也不写深度
    ds.depthCompareOp = VK_COMPARE_OP_LESS;
    VkPipelineColorBlendAttachmentState ba{};
    ba.colorWriteMask = 0xF;
    int bm = state & ST_BLEND;
    if (bm) {
        ba.blendEnable = VK_TRUE;
        ba.srcColorBlendFactor = ba.srcAlphaBlendFactor = bm == 2 ? VK_BLEND_FACTOR_ZERO : VK_BLEND_FACTOR_ONE;
        ba.dstColorBlendFactor = ba.dstAlphaBlendFactor = bm == 3 ? VK_BLEND_FACTOR_ONE : VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        ba.colorBlendOp = ba.alphaBlendOp = bm == 3 ? VK_BLEND_OP_REVERSE_SUBTRACT : VK_BLEND_OP_ADD;
    }
    VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1; cb.pAttachments = &ba;
    VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dy{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dy.dynamicStateCount = 2; dy.pDynamicStates = dyn;
    VkGraphicsPipelineCreateInfo pi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    pi.stageCount = 2; pi.pStages = st;
    pi.pVertexInputState = &vi; pi.pInputAssemblyState = &ia; pi.pViewportState = &vp; pi.pRasterizationState = &rs;
    pi.pMultisampleState = &ms; pi.pDepthStencilState = &ds; pi.pColorBlendState = &cb; pi.pDynamicState = &dy;
    pi.layout = c.pl; pi.renderPass = passOf(c, ci, 2, 1);
    VkPipeline pipe = VK_NULL_HANDLE;
    VkResult r = vkCreateGraphicsPipelines(c.dev, c.pcache, 1, &pi, nullptr, &pipe);
    if (r != VK_SUCCESS) { LOGW("Vulkan：建管线失败（程序 %d 状态 %d）：%d", prog, state, (int) r); pipe = VK_NULL_HANDLE; }
    c.pipes[key] = pipe;
    c.newPipes++;
    return pipe;
}

// ---------------- 贴图 ----------------
int newSlot(Ctx& c) {
    for (size_t i = 1; i < c.imgs.size(); i++) if (!c.imgs[i].alive) return (int) i;
    c.imgs.push_back(Img());
    return (int) c.imgs.size() - 1;
}

/** 把用到这张贴图的描述符集都作废 */
void dropSets(Ctx& c, int id) {
    for (auto it = c.sets.begin(); it != c.sets.end();) {
        uint64_t k = it->first;
        bool hit = false;
        for (int s = 0; s < 4; s++) if ((int) ((k >> (s * 16)) & 0xFFFF) == id) hit = true;
        if (hit) { later(c, 2, nullptr, nullptr, it->second, VK_NULL_HANDLE); it = c.sets.erase(it); } else ++it;
    }
}

/** 按像素建一张贴图（要的话生成缩小图），在上传缓冲里排好拷贝；fmt 是 RGBA8 或 R8（R8 当 alpha 用） */
bool texFill(Ctx& c, Img& m, const uint8_t* px, uint32_t w, uint32_t h, size_t stride, int bpp, bool alpha, bool mip) {
    VkFormat fmt = alpha ? VK_FORMAT_R8_UNORM : VK_FORMAT_R8G8B8A8_UNORM;
    uint32_t mips = 1;
    if (mip) { uint32_t s = w > h ? w : h; while (s > 1) { s >>= 1; mips++; } }
    VkComponentMapping swz = {VK_COMPONENT_SWIZZLE_ZERO, VK_COMPONENT_SWIZZLE_ZERO, VK_COMPONENT_SWIZZLE_ZERO, VK_COMPONENT_SWIZZLE_R};
    VkImageUsageFlags use = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | (mips > 1 ? VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0);
    if (!imgMake(c, w, h, mips, fmt, use, 1, false, VK_IMAGE_ASPECT_COLOR_BIT, alpha ? &swz : nullptr, m.img, m.mem, m.view)) return false;
    m.w = w; m.h = h; m.mips = mips; m.alive = true; m.fmt = fmt;
    int ob = alpha ? 1 : 4;
    Buf st;
    if (!bufMake(c, (VkDeviceSize) w * h * ob, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, st)) return false;
    uint8_t* d = (uint8_t*) st.p;
    for (uint32_t y = 0; y < h; y++) {
        const uint8_t* s = px + y * stride;
        uint8_t* o = d + (size_t) y * w * ob;
        if (bpp == ob) memcpy(o, s, (size_t) w * ob);
        else for (uint32_t x = 0; x < w; x++) { // RGB565 → RGBA8
            uint16_t v = (uint16_t) (s[x * 2] | (s[x * 2 + 1] << 8));
            o[x * 4] = (uint8_t) (((v >> 11) & 31) * 255 / 31); o[x * 4 + 1] = (uint8_t) (((v >> 5) & 63) * 255 / 63); o[x * 4 + 2] = (uint8_t) ((v & 31) * 255 / 31); o[x * 4 + 3] = 255;
        }
    }
    VkCommandBuffer cb = upCmd(c);
    barrier(cb, m.img, VK_IMAGE_ASPECT_COLOR_BIT, 0, mips, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkBufferImageCopy bc{};
    bc.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    bc.imageExtent = {w, h, 1};
    vkCmdCopyBufferToImage(cb, st.b, m.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bc);
    int32_t mw = (int32_t) w, mh = (int32_t) h;
    for (uint32_t i = 1; i < mips; i++) {
        barrier(cb, m.img, VK_IMAGE_ASPECT_COLOR_BIT, i - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        int32_t nw = mw > 1 ? mw / 2 : 1, nh = mh > 1 ? mh / 2 : 1;
        VkImageBlit b{};
        b.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, i - 1, 0, 1}; b.srcOffsets[1] = {mw, mh, 1};
        b.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, i, 0, 1}; b.dstOffsets[1] = {nw, nh, 1};
        vkCmdBlitImage(cb, m.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, m.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &b, VK_FILTER_LINEAR);
        barrier(cb, m.img, VK_IMAGE_ASPECT_COLOR_BIT, i - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        mw = nw; mh = nh;
    }
    barrier(cb, m.img, VK_IMAGE_ASPECT_COLOR_BIT, mips - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    later(c, 0, &st, nullptr, VK_NULL_HANDLE, VK_NULL_HANDLE);
    return true;
}

bool texFromBitmap(Ctx& c, JNIEnv* env, jobject bmp, bool mip, Img& m) {
    AndroidBitmapInfo bi;
    if (AndroidBitmap_getInfo(env, bmp, &bi) != ANDROID_BITMAP_RESULT_SUCCESS || bi.width == 0 || bi.height == 0) return false;
    int bpp = bi.format == ANDROID_BITMAP_FORMAT_RGBA_8888 ? 4 : bi.format == ANDROID_BITMAP_FORMAT_RGB_565 ? 2 : bi.format == ANDROID_BITMAP_FORMAT_A_8 ? 1 : 0;
    if (!bpp) { LOGW("Vulkan：不支持的位图格式 %d", bi.format); return false; }
    void* px = nullptr;
    if (AndroidBitmap_lockPixels(env, bmp, &px) != ANDROID_BITMAP_RESULT_SUCCESS || !px) return false;
    bool ok = texFill(c, m, (const uint8_t*) px, bi.width, bi.height, bi.stride, bpp, bpp == 1, mip);
    AndroidBitmap_unlockPixels(env, bmp);
    return ok;
}

/** 空的 RGB565 贴图（歌单的封面图集）：levels 级，内容各级自己往里传（texSub） */
bool atlasMake(Ctx& c, Img& m, uint32_t w, uint32_t h, uint32_t levels) {
    VkFormat fmt = VK_FORMAT_R5G6B5_UNORM_PACK16;
    if (!imgMake(c, w, h, levels, fmt, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, 1, false, VK_IMAGE_ASPECT_COLOR_BIT, nullptr, m.img, m.mem, m.view)) return false;
    m.w = w; m.h = h; m.mips = levels; m.alive = true; m.fmt = fmt;
    VkCommandBuffer cb = upCmd(c);
    barrier(cb, m.img, VK_IMAGE_ASPECT_COLOR_BIT, 0, levels, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, VK_ACCESS_SHADER_READ_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    return true;
}

/** 往贴图第 level 级的 (x, y) 处传一块位图（格式要和贴图一样：RGB565 的图集只收 RGB565 位图）；在路上的帧还在读的话，按提交的先后排在它们后面 */
bool texSub(Ctx& c, JNIEnv* env, Img& m, uint32_t level, int x, int y, jobject bmp) {
    AndroidBitmapInfo bi;
    if (AndroidBitmap_getInfo(env, bmp, &bi) != ANDROID_BITMAP_RESULT_SUCCESS || bi.width == 0 || bi.height == 0 || level >= m.mips) return false;
    int bpp = m.fmt == VK_FORMAT_R5G6B5_UNORM_PACK16 ? 2 : m.fmt == VK_FORMAT_R8_UNORM ? 1 : 4;
    int want = bpp == 2 ? ANDROID_BITMAP_FORMAT_RGB_565 : bpp == 1 ? ANDROID_BITMAP_FORMAT_A_8 : ANDROID_BITMAP_FORMAT_RGBA_8888;
    if ((int) bi.format != want) { LOGW("Vulkan：传一块的位图格式 %d 和贴图对不上", bi.format); return false; }
    void* px = nullptr;
    if (AndroidBitmap_lockPixels(env, bmp, &px) != ANDROID_BITMAP_RESULT_SUCCESS || !px) return false;
    Buf st;
    bool ok = bufMake(c, (VkDeviceSize) bi.width * bi.height * bpp, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, st);
    if (ok) for (uint32_t r = 0; r < bi.height; r++) memcpy((uint8_t*) st.p + (size_t) r * bi.width * bpp, (const uint8_t*) px + (size_t) r * bi.stride, (size_t) bi.width * bpp);
    AndroidBitmap_unlockPixels(env, bmp);
    if (!ok) return false;
    VkCommandBuffer cb = upCmd(c);
    barrier(cb, m.img, VK_IMAGE_ASPECT_COLOR_BIT, level, 1, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkBufferImageCopy bc{};
    bc.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, level, 0, 1};
    bc.imageOffset = {x, y, 0};
    bc.imageExtent = {bi.width, bi.height, 1};
    vkCmdCopyBufferToImage(cb, st.b, m.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bc);
    barrier(cb, m.img, VK_IMAGE_ASPECT_COLOR_BIT, level, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    later(c, 0, &st, nullptr, VK_NULL_HANDLE, VK_NULL_HANDLE);
    return true;
}

// ---------------- 离屏缓冲 ----------------
bool targetMake(Ctx& c, Img& m, uint32_t w, uint32_t h, bool depth, int samples) {
    m.target = true; m.depth = depth; m.samples = samples; m.w = w; m.h = h; m.mips = 1;
    VkFormat fmt = VK_FORMAT_R8G8B8A8_UNORM;
    if (!imgMake(c, w, h, 1, fmt, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, 1, false,
                 VK_IMAGE_ASPECT_COLOR_BIT, nullptr, m.img, m.mem, m.view)) return false;
    m.alive = true;
    if (samples > 1 && !imgMake(c, w, h, 1, fmt, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT, samples, true,
                                VK_IMAGE_ASPECT_COLOR_BIT, nullptr, m.ms, m.msMem, m.msView)) return false;
    if (depth && !imgMake(c, w, h, 1, c.depthFmt, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT, samples, true,
                          VK_IMAGE_ASPECT_DEPTH_BIT, nullptr, m.dImg, m.dMem, m.dView)) return false;
    m.cls = clsOf(c, fmt, samples, depth, false);
    std::vector<VkImageView> v;
    v.push_back(samples > 1 ? m.msView : m.view);
    if (depth) v.push_back(m.dView);
    if (samples > 1) v.push_back(m.view);
    VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fi.renderPass = passOf(c, m.cls, 2, 1); fi.attachmentCount = (uint32_t) v.size(); fi.pAttachments = v.data();
    fi.width = w; fi.height = h; fi.layers = 1;
    if (vkCreateFramebuffer(c.dev, &fi, nullptr, &m.fb) != VK_SUCCESS) return false;
    // 先清成黑的、放到"给着色器读"的布局（没画过就被取样也不出错）
    VkCommandBuffer cb = upCmd(c);
    barrier(cb, m.img, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkClearColorValue black{{0, 0, 0, 1}};
    VkImageSubresourceRange r{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(cb, m.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &r);
    barrier(cb, m.img, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    return true;
}

// ---------------- 交换链 ----------------
void swapFree(Ctx& c) {
    for (auto f : c.scFbs) vkDestroyFramebuffer(c.dev, f, nullptr);
    for (auto v : c.scViews) vkDestroyImageView(c.dev, v, nullptr);
    for (auto s : c.done) vkDestroySemaphore(c.dev, s, nullptr);
    c.scFbs.clear(); c.scViews.clear(); c.done.clear(); c.scImgs.clear();
    imgFree(c, c.scMs); imgFree(c, c.scDepth);
}

bool swapMake(Ctx& c) {
    VkSurfaceCapabilitiesKHR caps;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(c.gpu, c.surf, &caps) != VK_SUCCESS) return false;
    c.view = caps.currentExtent;
    if (c.view.width == 0xFFFFFFFF) c.view = {(uint32_t) ANativeWindow_getWidth(c.win), (uint32_t) ANativeWindow_getHeight(c.win)};
    if (c.view.width == 0 || c.view.height == 0) return false;
    c.pre = caps.currentTransform;
    bool side = c.pre & (VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR | VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR);
    c.ext = side ? VkExtent2D{c.view.height, c.view.width} : c.view;
    int deg = c.pre == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR ? 90 : c.pre == VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR ? 180 : c.pre == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR ? 270 : 0;
    float co = std::round(std::cos(deg * 3.14159265f / 180)), si = std::round(std::sin(deg * 3.14159265f / 180));
    // 先上下翻（屏幕的图第一行在上面，GL 的在下面），再转：[co -si; si co] × [1 0; 0 -1]
    c.rot[0] = co; c.rot[1] = si; c.rot[2] = si; c.rot[3] = -co;
    uint32_t count = caps.minImageCount + 1;
    if (caps.maxImageCount && count > caps.maxImageCount) count = caps.maxImageCount;
    VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    ci.surface = c.surf; ci.minImageCount = count; ci.imageFormat = c.scFmt; ci.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    ci.imageExtent = c.ext; ci.imageArrayLayers = 1; ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE; ci.preTransform = c.pre;
    ci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    ci.presentMode = VK_PRESENT_MODE_FIFO_KHR; ci.clipped = VK_TRUE; ci.oldSwapchain = c.sc;
    VkSwapchainKHR sc;
    if (vkCreateSwapchainKHR(c.dev, &ci, nullptr, &sc) != VK_SUCCESS) return false;
    swapFree(c);
    if (c.sc) vkDestroySwapchainKHR(c.dev, c.sc, nullptr);
    c.sc = sc;
    uint32_t n = 0;
    vkGetSwapchainImagesKHR(c.dev, c.sc, &n, nullptr);
    c.scImgs.resize(n);
    vkGetSwapchainImagesKHR(c.dev, c.sc, &n, c.scImgs.data());
    c.scCls = clsOf(c, c.scFmt, c.samples, true, true);
    if (c.samples > 1 && !imgMake(c, c.ext.width, c.ext.height, 1, c.scFmt, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT,
                                  c.samples, true, VK_IMAGE_ASPECT_COLOR_BIT, nullptr, c.scMs.img, c.scMs.mem, c.scMs.view)) return false;
    if (!imgMake(c, c.ext.width, c.ext.height, 1, c.depthFmt, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT,
                 c.samples, true, VK_IMAGE_ASPECT_DEPTH_BIT, nullptr, c.scDepth.img, c.scDepth.mem, c.scDepth.view)) return false;
    VkSemaphoreCreateInfo si2{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (uint32_t i = 0; i < n; i++) {
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vi.image = c.scImgs[i]; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = c.scFmt;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageView v;
        vkCreateImageView(c.dev, &vi, nullptr, &v);
        c.scViews.push_back(v);
        std::vector<VkImageView> at;
        at.push_back(c.samples > 1 ? c.scMs.view : v);
        at.push_back(c.scDepth.view);
        if (c.samples > 1) at.push_back(v);
        VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fi.renderPass = passOf(c, c.scCls, 2, 1); fi.attachmentCount = (uint32_t) at.size(); fi.pAttachments = at.data();
        fi.width = c.ext.width; fi.height = c.ext.height; fi.layers = 1;
        VkFramebuffer fb;
        if (vkCreateFramebuffer(c.dev, &fi, nullptr, &fb) != VK_SUCCESS) return false;
        c.scFbs.push_back(fb);
        VkSemaphore s;
        vkCreateSemaphore(c.dev, &si2, nullptr, &s);
        c.done.push_back(s);
    }
    LOGW("Vulkan：交换链 %u×%u，%u 张图，预旋转 %d°，采样 %d", c.ext.width, c.ext.height, n, deg, c.samples);
    return true;
}

bool swapChanged(Ctx& c) {
    VkSurfaceCapabilitiesKHR caps;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(c.gpu, c.surf, &caps) != VK_SUCCESS) return false;
    return caps.currentTransform != c.pre || (caps.currentExtent.width != 0xFFFFFFFF && (caps.currentExtent.width != c.view.width || caps.currentExtent.height != c.view.height));
}

bool surfMake(Ctx& c, JNIEnv* env, jobject surface) {
    c.win = ANativeWindow_fromSurface(env, surface);
    if (!c.win) return false;
    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = c.win;
    return vkCreateAndroidSurfaceKHR(c.inst, &sci, nullptr, &c.surf) == VK_SUCCESS;
}

void surfFree(Ctx& c) {
    if (c.dev) vkDeviceWaitIdle(c.dev);
    swapFree(c);
    if (c.sc) vkDestroySwapchainKHR(c.dev, c.sc, nullptr);
    c.sc = VK_NULL_HANDLE;
    if (c.surf) vkDestroySurfaceKHR(c.inst, c.surf, nullptr);
    c.surf = VK_NULL_HANDLE;
    if (c.win) ANativeWindow_release(c.win);
    c.win = nullptr;
}

std::string cacheFile(Ctx& c) { return c.cacheDir + "/vk.pcache"; }

void saveCache(Ctx& c) {
    if (!c.pcache || !c.newPipes) return;
    size_t n = 0;
    vkGetPipelineCacheData(c.dev, c.pcache, &n, nullptr);
    std::vector<char> d(n);
    if (vkGetPipelineCacheData(c.dev, c.pcache, &n, d.data()) != VK_SUCCESS) return;
    FILE* f = fopen(cacheFile(c).c_str(), "wb");
    if (f) { fwrite(d.data(), 1, n, f); fclose(f); }
    c.newPipes = 0;
}

bool ringMake(Ctx& c, VkDeviceSize seg) {
    if (c.ring.b) later(c, 0, &c.ring, nullptr, VK_NULL_HANDLE, VK_NULL_HANDLE);
    c.ring = Buf();
    if (!bufMake(c, seg * FR, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, c.ring)) return false;
    c.seg = seg;
    VkDescriptorBufferInfo bi[2] = {{c.ring.b, 0, BLOCK}, {c.ring.b, 0, BLOCK}};
    VkWriteDescriptorSet w[2] = {{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}, {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}};
    for (int i = 0; i < 2; i++) { w[i].dstSet = c.set0; w[i].dstBinding = i; w[i].descriptorCount = 1; w[i].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC; w[i].pBufferInfo = &bi[i]; }
    vkUpdateDescriptorSets(c.dev, 2, w, 0, nullptr);
    return true;
}

bool iringMake(Ctx& c, VkDeviceSize seg) {
    if (c.iring.b) later(c, 0, &c.iring, nullptr, VK_NULL_HANDLE, VK_NULL_HANDLE);
    c.iring = Buf();
    if (!bufMake(c, seg * FR, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, c.iring)) return false;
    c.iseg = seg;
    return true;
}

bool create(Ctx& c, JNIEnv* env, jobject surface, int wantSamples) {
    VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    ai.pApplicationName = "liuguang";
    ai.apiVersion = VK_API_VERSION_1_0;
    const char* iext[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &ai; ici.enabledExtensionCount = 2; ici.ppEnabledExtensionNames = iext;
    if (vkCreateInstance(&ici, nullptr, &c.inst) != VK_SUCCESS) { LOGW("Vulkan：建实例失败"); return false; }
    if (!surfMake(c, env, surface)) { LOGW("Vulkan：建画布失败"); return false; }
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(c.inst, &n, nullptr);
    if (!n) { LOGW("Vulkan：没有显卡"); return false; }
    std::vector<VkPhysicalDevice> gpus(n);
    vkEnumeratePhysicalDevices(c.inst, &n, gpus.data());
    c.gpu = gpus[0];
    vkGetPhysicalDeviceMemoryProperties(c.gpu, &c.memp);
    VkPhysicalDeviceProperties pp;
    vkGetPhysicalDeviceProperties(c.gpu, &pp);
    c.uboAlign = (uint32_t) pp.limits.minUniformBufferOffsetAlignment;
    if (c.uboAlign < 16) c.uboAlign = 16;
    VkPhysicalDeviceFeatures have;
    vkGetPhysicalDeviceFeatures(c.gpu, &have);
    n = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(c.gpu, &n, nullptr);
    std::vector<VkQueueFamilyProperties> qfs(n);
    vkGetPhysicalDeviceQueueFamilyProperties(c.gpu, &n, qfs.data());
    bool found = false;
    for (uint32_t i = 0; i < n && !found; i++) {
        VkBool32 pres = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(c.gpu, i, c.surf, &pres);
        if ((qfs[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && pres) { c.qf = i; found = true; }
    }
    if (!found) { LOGW("Vulkan：没有能画又能显示的队列"); return false; }
    float prio = 1;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = c.qf; qci.queueCount = 1; qci.pQueuePriorities = &prio;
    VkPhysicalDeviceFeatures feat{};
    feat.largePoints = have.largePoints; // 浮尘、光点是几个像素大的点
    const char* dext[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci; dci.enabledExtensionCount = 1; dci.ppEnabledExtensionNames = dext; dci.pEnabledFeatures = &feat;
    if (vkCreateDevice(c.gpu, &dci, nullptr, &c.dev) != VK_SUCCESS) { LOGW("Vulkan：建设备失败"); return false; }
    vkGetDeviceQueue(c.dev, c.qf, 0, &c.q);
    // 屏幕格式：要 RGBA8 / BGRA8（不要 sRGB：GL 那边也是直接写）
    uint32_t fn = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(c.gpu, c.surf, &fn, nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(fn);
    vkGetPhysicalDeviceSurfaceFormatsKHR(c.gpu, c.surf, &fn, fmts.data());
    c.scFmt = fmts.empty() ? VK_FORMAT_R8G8B8A8_UNORM : fmts[0].format;
    for (auto& f : fmts) if (f.format == VK_FORMAT_B8G8R8A8_UNORM) c.scFmt = f.format;
    for (auto& f : fmts) if (f.format == VK_FORMAT_R8G8B8A8_UNORM) c.scFmt = f.format;
    // 深度格式：同 GL 的 24 位，没有就退
    VkFormat dfs[] = {VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D32_SFLOAT, VK_FORMAT_D16_UNORM};
    for (VkFormat f : dfs) {
        VkFormatProperties fp;
        vkGetPhysicalDeviceFormatProperties(c.gpu, f, &fp);
        if (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) { c.depthFmt = f; break; }
    }
    VkSampleCountFlags sc = pp.limits.framebufferColorSampleCounts & pp.limits.framebufferDepthSampleCounts;
    c.samples = 1;
    if (wantSamples >= 4 && (sc & VK_SAMPLE_COUNT_4_BIT)) c.samples = 4;
    else if (wantSamples >= 2 && (sc & VK_SAMPLE_COUNT_2_BIT)) c.samples = 2;
    // 描述符：集 0 是两个动态 UBO（顶点、片元的 uniform 块），集 1 是 4 张贴图
    VkDescriptorSetLayoutBinding b0[2] = {{0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1, VK_SHADER_STAGE_VERTEX_BIT, nullptr},
                                          {1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr}};
    VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    li.bindingCount = 2; li.pBindings = b0;
    vkCreateDescriptorSetLayout(c.dev, &li, nullptr, &c.dsl0);
    VkDescriptorSetLayoutBinding b1[4];
    for (int i = 0; i < 4; i++) b1[i] = {(uint32_t) i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr};
    li.bindingCount = 4; li.pBindings = b1;
    vkCreateDescriptorSetLayout(c.dev, &li, nullptr, &c.dsl1);
    VkDescriptorSetLayout sl[2] = {c.dsl0, c.dsl1};
    VkPushConstantRange pcr{VK_SHADER_STAGE_VERTEX_BIT, 0, 16};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 2; pli.pSetLayouts = sl; pli.pushConstantRangeCount = 1; pli.pPushConstantRanges = &pcr;
    vkCreatePipelineLayout(c.dev, &pli, nullptr, &c.pl);
    VkDescriptorPoolSize ps0{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 8};
    VkDescriptorPoolCreateInfo pci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pci.maxSets = 4; pci.poolSizeCount = 1; pci.pPoolSizes = &ps0;
    vkCreateDescriptorPool(c.dev, &pci, nullptr, &c.pool0);
    VkDescriptorPoolSize ps1{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 4096};
    pci.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT; pci.maxSets = 1024; pci.pPoolSizes = &ps1;
    vkCreateDescriptorPool(c.dev, &pci, nullptr, &c.pool1);
    VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dai.descriptorPool = c.pool0; dai.descriptorSetCount = 1; dai.pSetLayouts = &c.dsl0;
    vkAllocateDescriptorSets(c.dev, &dai, &c.set0);
    VkSamplerCreateInfo smi{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    smi.magFilter = smi.minFilter = VK_FILTER_LINEAR; smi.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    smi.addressModeU = smi.addressModeV = smi.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    smi.maxLod = 16;
    vkCreateSampler(c.dev, &smi, nullptr, &c.sampler);
    // 管线缓存：上次存的读进来（驱动不认就是空的）
    std::vector<char> cd;
    FILE* f = fopen(cacheFile(c).c_str(), "rb");
    if (f) { fseek(f, 0, SEEK_END); long len = ftell(f); fseek(f, 0, SEEK_SET); if (len > 0) { cd.resize(len); if (fread(cd.data(), 1, len, f) != (size_t) len) cd.clear(); } fclose(f); }
    VkPipelineCacheCreateInfo pcc{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
    pcc.initialDataSize = cd.size(); pcc.pInitialData = cd.empty() ? nullptr : cd.data();
    if (vkCreatePipelineCache(c.dev, &pcc, nullptr, &c.pcache) != VK_SUCCESS) { pcc.initialDataSize = 0; pcc.pInitialData = nullptr; vkCreatePipelineCache(c.dev, &pcc, nullptr, &c.pcache); }
    // 命令、同步
    VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; cpi.queueFamilyIndex = c.qf;
    vkCreateCommandPool(c.dev, &cpi, nullptr, &c.cpool);
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = c.cpool; cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount = FR;
    vkAllocateCommandBuffers(c.dev, &cai, c.cmd);
    vkAllocateCommandBuffers(c.dev, &cai, c.up);
    VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    for (int i = 0; i < FR; i++) { vkCreateSemaphore(c.dev, &si, nullptr, &c.acq[i]); vkCreateFence(c.dev, &fci, nullptr, &c.fence[i]); }
    if (!ringMake(c, 256 * 1024) || !iringMake(c, 64 * 1024)) { LOGW("Vulkan：建 uniform / 实例缓冲失败"); return false; }
    // 贴图 0：1×1 黑的（GL 里没绑贴图时取到的是 0,0,0,1）
    c.imgs.push_back(Img());
    uint8_t px[4] = {0, 0, 0, 255};
    if (!texFill(c, c.imgs[0], px, 1, 1, 4, 4, false, false)) return false;
    c.layouts.push_back(Layout()); // 布局 0 不用
    c.progs.push_back(Prog());     // 程序 0 不用
    c.meshes.push_back(Mesh());    // 网格 0 不用
    if (!swapMake(c)) { LOGW("Vulkan：建交换链失败"); return false; }
    LOGW("Vulkan：%s，驱动 %u.%u.%u，深度格式 %d，屏幕格式 %d", pp.deviceName, VK_VERSION_MAJOR(pp.driverVersion), VK_VERSION_MINOR(pp.driverVersion),
         VK_VERSION_PATCH(pp.driverVersion), (int) c.depthFmt, (int) c.scFmt);
    return true;
}

void destroy(Ctx& c) {
    if (c.dev) {
        vkDeviceWaitIdle(c.dev);
        saveCache(c);
        reap(c, true);
        for (auto& p : c.pipes) if (p.second) vkDestroyPipeline(c.dev, p.second, nullptr);
        for (auto& k : c.classes) for (auto& a : k.rp) for (auto r : a) if (r) vkDestroyRenderPass(c.dev, r, nullptr);
        for (auto& m : c.imgs) imgFree(c, m);
        for (auto& m : c.meshes) { bufFree(c, m.vb); bufFree(c, m.ib); }
        for (auto& p : c.progs) { if (p.vs) vkDestroyShaderModule(c.dev, p.vs, nullptr); if (p.fs) vkDestroyShaderModule(c.dev, p.fs, nullptr); }
        bufFree(c, c.ring);
        bufFree(c, c.iring);
        if (c.pcache) vkDestroyPipelineCache(c.dev, c.pcache, nullptr);
        if (c.sampler) vkDestroySampler(c.dev, c.sampler, nullptr);
        if (c.pool0) vkDestroyDescriptorPool(c.dev, c.pool0, nullptr);
        if (c.pool1) vkDestroyDescriptorPool(c.dev, c.pool1, nullptr);
        if (c.pl) vkDestroyPipelineLayout(c.dev, c.pl, nullptr);
        if (c.dsl0) vkDestroyDescriptorSetLayout(c.dev, c.dsl0, nullptr);
        if (c.dsl1) vkDestroyDescriptorSetLayout(c.dev, c.dsl1, nullptr);
        for (int i = 0; i < FR; i++) { if (c.acq[i]) vkDestroySemaphore(c.dev, c.acq[i], nullptr); if (c.fence[i]) vkDestroyFence(c.dev, c.fence[i], nullptr); }
        if (c.cpool) vkDestroyCommandPool(c.dev, c.cpool, nullptr);
    }
    surfFree(c);
    if (c.dev) vkDestroyDevice(c.dev, nullptr);
    if (c.inst) vkDestroyInstance(c.inst, nullptr);
}

VkShaderModule module(Ctx& c, const char* key) {
    std::string name = std::string("vk_") + key + ".spv";
    AAsset* a = AAssetManager_open(c.am, name.c_str(), AASSET_MODE_BUFFER);
    if (!a) { LOGW("Vulkan：assets 里没有 %s（着色器原文和打包时的不一样？）", name.c_str()); return VK_NULL_HANDLE; }
    size_t n = AAsset_getLength(a);
    std::vector<uint32_t> code((n + 3) / 4);
    AAsset_read(a, code.data(), n);
    AAsset_close(a);
    VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    ci.codeSize = n; ci.pCode = code.data();
    VkShaderModule m = VK_NULL_HANDLE;
    vkCreateShaderModule(c.dev, &ci, nullptr, &m);
    return m;
}

VkDescriptorSet setOf(Ctx& c, const int* t) {
    uint64_t key = 0;
    for (int s = 0; s < 4; s++) {
        int id = t[s];
        if (id < 0 || id >= (int) c.imgs.size() || !c.imgs[id].alive) id = 0;
        key |= (uint64_t) (id & 0xFFFF) << (s * 16);
    }
    auto it = c.sets.find(key);
    if (it != c.sets.end()) return it->second;
    VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dai.descriptorPool = c.pool1; dai.descriptorSetCount = 1; dai.pSetLayouts = &c.dsl1;
    VkDescriptorSet s = VK_NULL_HANDLE;
    if (vkAllocateDescriptorSets(c.dev, &dai, &s) != VK_SUCCESS) { LOGW("Vulkan：描述符集用完了（%zu 个）", c.sets.size()); return VK_NULL_HANDLE; }
    VkDescriptorImageInfo ii[4];
    VkWriteDescriptorSet w[4];
    for (int k = 0; k < 4; k++) {
        int id = (int) ((key >> (k * 16)) & 0xFFFF);
        ii[k] = {c.sampler, c.imgs[id].view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        w[k] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        w[k].dstSet = s; w[k].dstBinding = (uint32_t) k; w[k].descriptorCount = 1; w[k].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; w[k].pImageInfo = &ii[k];
    }
    vkUpdateDescriptorSets(c.dev, 4, w, 0, nullptr);
    c.sets[key] = s;
    return s;
}

// ---------------- 一帧 ----------------
/** 返回 0 画好了、1 交换链要重建（这帧没显示）、-1 出错 */
int frame(Ctx& c, const int* cmd, int n, const float* ubo, int nu, const float* ins, int ni) {
    if (!c.sc) return 1;
    waitSlot(c);
    int f = (int) (c.submitted % FR);
    VkDeviceSize need = (VkDeviceSize) nu * 4 + BLOCK, ineed = (VkDeviceSize) ni * 4;
    if (need > c.seg || ineed > c.iseg) {
        vkDeviceWaitIdle(c.dev);
        VkDeviceSize s = c.seg; while (s < need) s *= 2;
        VkDeviceSize is = c.iseg; while (is < ineed) is *= 2;
        if ((s != c.seg && !ringMake(c, s)) || (is != c.iseg && !iringMake(c, is))) return -1;
    }
    VkDeviceSize base = c.seg * f, ibase = c.iseg * f;
    memcpy((char*) c.ring.p + base, ubo, (size_t) nu * 4);
    if (ni) memcpy((char*) c.iring.p + ibase, ins, (size_t) ni * 4);
    // 这一帧有画到屏幕上的：先取一张交换链的图
    bool screen = false;
    for (int i = 0; i < n; i += cmdLen(cmd[i])) if (cmd[i] == CMD_PASS && cmd[i + 1] == 0) screen = true;
    uint32_t idx = 0;
    if (screen) {
        VkResult r = vkAcquireNextImageKHR(c.dev, c.sc, UINT64_MAX, c.acq[f], VK_NULL_HANDLE, &idx); // 安卓的加载层没实现有限的超时（给了还每次打一行警告）
        if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_TIMEOUT || r == VK_NOT_READY) return 1;
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) { LOGW("Vulkan：取交换链图失败 %d", (int) r); return -1; }
    }
    VkCommandBuffer cb = c.cmd[f];
    vkResetCommandBuffer(cb, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cb, &bi);
    bool open = false;
    int ci = -1, lastMesh = -1;
    VkPipeline lastPipe = VK_NULL_HANDLE;
    VkDescriptorSet lastSet = VK_NULL_HANDLE;
    uint32_t pw = 0, ph = 0;
    for (int i = 0; i < n;) {
        int op = cmd[i];
        if (op == CMD_PASS) {
            int t = cmd[i + 1], fl = cmd[i + 2];
            float col[4]; memcpy(col, &cmd[i + 3], 16);
            i += 7;
            if (open) vkCmdEndRenderPass(cb);
            open = false;
            VkFramebuffer fb;
            if (t == 0) { ci = c.scCls; fb = c.scFbs[idx]; pw = c.ext.width; ph = c.ext.height; }
            else {
                if (t >= (int) c.imgs.size() || !c.imgs[t].alive || !c.imgs[t].target) continue;
                Img& m = c.imgs[t];
                ci = m.cls; fb = m.fb; pw = m.w; ph = m.h;
            }
            Cls& k = c.classes[ci];
            VkClearValue cv[3];
            cv[0].color = {{col[0], col[1], col[2], col[3]}};
            cv[1].depthStencil = {1, 0};
            cv[2].color = cv[0].color;
            if (!k.depth) cv[1] = cv[0];
            VkRenderPassBeginInfo rb{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
            rb.renderPass = passOf(c, ci, (fl & 1) ? 1 : 0, (fl & 2) ? 1 : 0);
            rb.framebuffer = fb; rb.renderArea = {{0, 0}, {pw, ph}};
            rb.clearValueCount = 1 + (k.depth ? 1 : 0) + (k.samples > 1 ? 1 : 0); rb.pClearValues = cv;
            vkCmdBeginRenderPass(cb, &rb, VK_SUBPASS_CONTENTS_INLINE);
            open = true;
            VkViewport vp{0, 0, (float) pw, (float) ph, 0, 1};
            VkRect2D sr{{0, 0}, {pw, ph}};
            vkCmdSetViewport(cb, 0, 1, &vp);
            vkCmdSetScissor(cb, 0, 1, &sr);
            static const float ID[4] = {1, 0, 0, 1};
            vkCmdPushConstants(cb, c.pl, VK_SHADER_STAGE_VERTEX_BIT, 0, 16, t == 0 ? c.rot : ID);
            lastPipe = VK_NULL_HANDLE; lastSet = VK_NULL_HANDLE; lastMesh = -1;
        } else if (op == CMD_DRAW) {
            int prog = cmd[i + 1], state = cmd[i + 2], vo = cmd[i + 3], fo = cmd[i + 4], mesh = cmd[i + 9], points = cmd[i + 10];
            int inst = cmd[i + 11], ioff = cmd[i + 12], icount = cmd[i + 13];
            const int* tx = &cmd[i + 5];
            i += 14;
            if (!open || prog <= 0 || prog >= (int) c.progs.size() || mesh <= 0 || mesh >= (int) c.meshes.size()) continue;
            Mesh& m = c.meshes[mesh];
            if (!m.alive || !c.progs[prog].alive || (inst && icount <= 0)) continue;
            VkPipeline p = pipeOf(c, prog, state, ci, m.layout, points != 0, inst);
            if (!p) continue;
            if (p != lastPipe) { vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, p); lastPipe = p; }
            uint32_t dyn[2] = {(uint32_t) (base + vo), (uint32_t) (base + fo)};
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, c.pl, 0, 1, &c.set0, 2, dyn);
            VkDescriptorSet s = setOf(c, tx);
            if (!s) continue;
            if (s != lastSet) { vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, c.pl, 1, 1, &s, 0, nullptr); lastSet = s; }
            if (mesh != lastMesh) {
                VkDeviceSize o = 0;
                vkCmdBindVertexBuffers(cb, 0, 1, &m.vb.b, &o);
                if (m.indexed) vkCmdBindIndexBuffer(cb, m.ib.b, 0, VK_INDEX_TYPE_UINT16);
                lastMesh = mesh;
            }
            if (inst) {
                VkDeviceSize io = ibase + (VkDeviceSize) ioff;
                vkCmdBindVertexBuffers(cb, 1, 1, &c.iring.b, &io);
            }
            uint32_t ninst = inst ? (uint32_t) icount : 1;
            if (m.indexed) vkCmdDrawIndexed(cb, m.count, ninst, 0, 0, 0);
            else vkCmdDraw(cb, m.count, ninst, 0, 0);
        } else if (op == CMD_COPY) {
            int s = cmd[i + 1], d = cmd[i + 2];
            i += 3;
            if (open) { vkCmdEndRenderPass(cb); open = false; }
            if (s <= 0 || d <= 0 || s >= (int) c.imgs.size() || d >= (int) c.imgs.size() || !c.imgs[s].alive || !c.imgs[d].alive) continue;
            Img& a = c.imgs[s];
            Img& b = c.imgs[d];
            VkPipelineStageFlags pre = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            VkAccessFlags after = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            barrier(cb, a.img, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, pre, VK_PIPELINE_STAGE_TRANSFER_BIT);
            barrier(cb, b.img, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, pre, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkImageCopy rc{};
            rc.srcSubresource = rc.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            rc.extent = {a.w < b.w ? a.w : b.w, a.h < b.h ? a.h : b.h, 1};
            vkCmdCopyImage(cb, a.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, b.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &rc);
            barrier(cb, a.img, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT, after, VK_PIPELINE_STAGE_TRANSFER_BIT, pre);
            barrier(cb, b.img, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, after, VK_PIPELINE_STAGE_TRANSFER_BIT, pre);
        } else if (op == CMD_CLEAR) {
            int fl = cmd[i + 1];
            float col[4]; memcpy(col, &cmd[i + 2], 16);
            i += 6;
            if (!open) continue;
            VkClearAttachment ca[2];
            uint32_t na = 0;
            if (fl & 1) { ca[na].aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; ca[na].colorAttachment = 0; ca[na].clearValue.color = {{col[0], col[1], col[2], col[3]}}; na++; }
            if ((fl & 2) && c.classes[ci].depth) { ca[na].aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT; ca[na].colorAttachment = 0; ca[na].clearValue.depthStencil = {1, 0}; na++; }
            VkClearRect cr{{{0, 0}, {pw, ph}}, 0, 1};
            if (na) vkCmdClearAttachments(cb, na, ca, 1, &cr);
        } else { LOGW("Vulkan：命令不对 %d", op); break; }
    }
    if (open) vkCmdEndRenderPass(cb);
    vkEndCommandBuffer(cb);
    VkCommandBuffer cbs[2];
    uint32_t ncb = 0;
    if (c.upOpen) { vkEndCommandBuffer(c.up[f]); cbs[ncb++] = c.up[f]; c.upOpen = false; }
    cbs[ncb++] = cb;
    VkPipelineStageFlags ws = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = ncb; si.pCommandBuffers = cbs;
    if (screen) { si.waitSemaphoreCount = 1; si.pWaitSemaphores = &c.acq[f]; si.pWaitDstStageMask = &ws; si.signalSemaphoreCount = 1; si.pSignalSemaphores = &c.done[idx]; }
    vkResetFences(c.dev, 1, &c.fence[f]);
    VkResult r = vkQueueSubmit(c.q, 1, &si, c.fence[f]);
    c.submitted++;
    c.waited = false;
    if (r != VK_SUCCESS) { LOGW("Vulkan：提交失败 %d", (int) r); return -1; }
    if (!screen) return 0;
    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1; pi.pWaitSemaphores = &c.done[idx];
    pi.swapchainCount = 1; pi.pSwapchains = &c.sc; pi.pImageIndices = &idx;
    r = vkQueuePresentKHR(c.q, &pi);
    if (r == VK_ERROR_OUT_OF_DATE_KHR || (r == VK_SUBOPTIMAL_KHR && swapChanged(c))) return 1;
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) { LOGW("Vulkan：显示失败 %d", (int) r); return -1; }
    if (c.newPipes && c.submitted % 600 == 0) saveCache(c);
    return 0;
}

Ctx* ctx(jlong p) { return reinterpret_cast<Ctx*>(p); }

} // namespace

#define JF(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_x3card_liuguang_Vk_##name

JF(jlong, nCreate)(JNIEnv* env, jclass, jobject surface, jobject assets, jstring cacheDir, jint samples) {
    Ctx* c = new Ctx();
    c->am = AAssetManager_fromJava(env, assets);
    const char* cd = env->GetStringUTFChars(cacheDir, nullptr);
    c->cacheDir = cd;
    env->ReleaseStringUTFChars(cacheDir, cd);
    if (!create(*c, env, surface, samples)) { destroy(*c); delete c; return 0; }
    return reinterpret_cast<jlong>(c);
}

JF(jintArray, nInfo)(JNIEnv* env, jclass, jlong p) {
    Ctx* c = ctx(p);
    jint v[2] = {(jint) c->uboAlign, c->samples};
    jintArray a = env->NewIntArray(2);
    env->SetIntArrayRegion(a, 0, 2, v);
    return a;
}

JF(jboolean, nSurface)(JNIEnv* env, jclass, jlong p, jobject surface) {
    Ctx* c = ctx(p);
    surfFree(*c);
    if (!surfMake(*c, env, surface)) return JNI_FALSE;
    VkBool32 pres = VK_FALSE;
    vkGetPhysicalDeviceSurfaceSupportKHR(c->gpu, c->qf, c->surf, &pres);
    return pres && swapMake(*c) ? JNI_TRUE : JNI_FALSE;
}

JF(void, nSurfaceLost)(JNIEnv*, jclass, jlong p) { surfFree(*ctx(p)); }

JF(jboolean, nResize)(JNIEnv*, jclass, jlong p, jboolean force) {
    Ctx* c = ctx(p);
    if (!c->surf) return JNI_FALSE;
    if (!force && c->sc && !swapChanged(*c)) return JNI_TRUE;
    vkDeviceWaitIdle(c->dev);
    return swapMake(*c) ? JNI_TRUE : JNI_FALSE;
}

JF(jint, nProg)(JNIEnv* env, jclass, jlong p, jstring vs, jstring fs) {
    Ctx* c = ctx(p);
    const char* a = env->GetStringUTFChars(vs, nullptr);
    const char* b = env->GetStringUTFChars(fs, nullptr);
    Prog pr;
    pr.vs = module(*c, a);
    pr.fs = module(*c, b);
    env->ReleaseStringUTFChars(vs, a);
    env->ReleaseStringUTFChars(fs, b);
    pr.alive = pr.vs && pr.fs;
    c->progs.push_back(pr);
    return (jint) c->progs.size() - 1;
}

JF(jint, nMesh)(JNIEnv* env, jclass, jlong p, jfloatArray data, jintArray sizes, jshortArray index) {
    Ctx* c = ctx(p);
    int ns = env->GetArrayLength(sizes);
    std::vector<int> sz(ns);
    env->GetIntArrayRegion(sizes, 0, ns, sz.data());
    int stride = 0;
    for (int s : sz) stride += s;
    int layout = -1;
    for (size_t i = 1; i < c->layouts.size(); i++) if (c->layouts[i].sizes == sz) layout = (int) i;
    if (layout < 0) { Layout L; L.sizes = sz; L.stride = stride; c->layouts.push_back(L); layout = (int) c->layouts.size() - 1; }
    Mesh m;
    m.layout = layout;
    int nd = env->GetArrayLength(data);
    if (!bufMake(*c, (VkDeviceSize) nd * 4, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, m.vb)) return 0;
    env->GetFloatArrayRegion(data, 0, nd, (jfloat*) m.vb.p);
    if (index) {
        int ni = env->GetArrayLength(index);
        if (!bufMake(*c, (VkDeviceSize) ni * 2, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, m.ib)) { bufFree(*c, m.vb); return 0; }
        env->GetShortArrayRegion(index, 0, ni, (jshort*) m.ib.p);
        m.indexed = true; m.count = (uint32_t) ni;
    } else m.count = (uint32_t) (nd / stride);
    m.alive = true;
    for (size_t i = 1; i < c->meshes.size(); i++) if (!c->meshes[i].alive) { c->meshes[i] = m; return (jint) i; }
    c->meshes.push_back(m);
    return (jint) c->meshes.size() - 1;
}

JF(void, nMeshData)(JNIEnv* env, jclass, jlong p, jint id, jfloatArray data) {
    Ctx* c = ctx(p);
    if (id <= 0 || id >= (jint) c->meshes.size() || !c->meshes[id].alive) return;
    Mesh& m = c->meshes[id];
    int nd = env->GetArrayLength(data);
    Buf nb;
    if (!bufMake(*c, (VkDeviceSize) nd * 4, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, nb)) return;
    env->GetFloatArrayRegion(data, 0, nd, (jfloat*) nb.p);
    later(*c, 0, &m.vb, nullptr, VK_NULL_HANDLE, VK_NULL_HANDLE); // 在路上的帧还在读旧的
    m.vb = nb;
}

JF(void, nMeshFree)(JNIEnv*, jclass, jlong p, jint id) {
    Ctx* c = ctx(p);
    if (id <= 0 || id >= (jint) c->meshes.size() || !c->meshes[id].alive) return;
    Mesh& m = c->meshes[id];
    later(*c, 0, &m.vb, nullptr, VK_NULL_HANDLE, VK_NULL_HANDLE);
    if (m.indexed) later(*c, 0, &m.ib, nullptr, VK_NULL_HANDLE, VK_NULL_HANDLE);
    m = Mesh();
}

JF(jint, nTex)(JNIEnv* env, jclass, jlong p, jobject bmp, jboolean mip) {
    Ctx* c = ctx(p);
    int id = newSlot(*c);
    if (!texFromBitmap(*c, env, bmp, mip, c->imgs[id])) { imgFree(*c, c->imgs[id]); return 0; }
    return id;
}

JF(void, nTexData)(JNIEnv* env, jclass, jlong p, jint id, jobject bmp) {
    Ctx* c = ctx(p);
    if (id <= 0 || id >= (jint) c->imgs.size() || !c->imgs[id].alive || c->imgs[id].target) return;
    Img old = c->imgs[id];
    Img nm;
    if (!texFromBitmap(*c, env, bmp, old.mips > 1, nm)) { imgFree(*c, nm); return; }
    dropSets(*c, id);
    later(*c, 1, nullptr, &old, VK_NULL_HANDLE, VK_NULL_HANDLE);
    c->imgs[id] = nm;
}

JF(void, nTexFree)(JNIEnv*, jclass, jlong p, jint id) {
    Ctx* c = ctx(p);
    if (id <= 0 || id >= (jint) c->imgs.size() || !c->imgs[id].alive) return;
    dropSets(*c, id);
    later(*c, 1, nullptr, &c->imgs[id], VK_NULL_HANDLE, VK_NULL_HANDLE);
    c->imgs[id] = Img();
}

JF(jint, nTarget)(JNIEnv*, jclass, jlong p, jint w, jint h, jboolean depth, jint samples) {
    Ctx* c = ctx(p);
    int s = samples >= 4 ? 4 : samples >= 2 ? 2 : 1;
    if (s > c->samples) s = c->samples;
    int id = newSlot(*c);
    if (!targetMake(*c, c->imgs[id], (uint32_t) w, (uint32_t) h, depth, s)) { LOGW("Vulkan：建离屏缓冲失败"); imgFree(*c, c->imgs[id]); return 0; }
    return id;
}

JF(void, nBegin)(JNIEnv*, jclass, jlong p) { waitSlot(*ctx(p)); }

JF(jint, nFrame)(JNIEnv* env, jclass, jlong p, jintArray cmd, jint n, jfloatArray ubo, jint nu, jfloatArray ins, jint ni) {
    Ctx* c = ctx(p);
    jint* cm = (jint*) env->GetPrimitiveArrayCritical(cmd, nullptr);
    jfloat* u = (jfloat*) env->GetPrimitiveArrayCritical(ubo, nullptr);
    jfloat* in = ni > 0 ? (jfloat*) env->GetPrimitiveArrayCritical(ins, nullptr) : nullptr;
    int r = frame(*c, cm, n, u, nu, in, ni);
    if (in) env->ReleasePrimitiveArrayCritical(ins, in, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(ubo, u, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(cmd, cm, JNI_ABORT);
    return r;
}

JF(jint, nAtlas)(JNIEnv*, jclass, jlong p, jint w, jint h, jint levels) {
    Ctx* c = ctx(p);
    int id = newSlot(*c);
    if (!atlasMake(*c, c->imgs[id], (uint32_t) w, (uint32_t) h, (uint32_t) levels)) { imgFree(*c, c->imgs[id]); return 0; }
    return id;
}

JF(void, nTexSub)(JNIEnv* env, jclass, jlong p, jint id, jint level, jint x, jint y, jobject bmp) {
    Ctx* c = ctx(p);
    if (id <= 0 || id >= (jint) c->imgs.size() || !c->imgs[id].alive || c->imgs[id].target) return;
    texSub(*c, env, c->imgs[id], (uint32_t) level, x, y, bmp);
}

JF(void, nDestroy)(JNIEnv*, jclass, jlong p) {
    Ctx* c = ctx(p);
    destroy(*c);
    delete c;
}
