// 音乐接口用到的签名与加解密：md5、HmacSHA256、AES-ECB（PKCS5），供 MusicNative 调。
// 网易云用系统 RSA 签名，这里只给出它需要的参数（C 里不做大数运算）。
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

// ---- 构建时生成的参数（music_params.h）；缺失时为空，音乐接口不启用 ----
#if __has_include("music_params.h")
#include "music_params.h"
#else
static const unsigned char MASK[] = {0};
static const unsigned char B_KG[] = {0}; static const int B_KG_N = 0;
static const unsigned char B_QQ[] = {0}; static const int B_QQ_N = 0;
static const unsigned char B_NE[] = {0}; static const int B_NE_N = 0;
static const unsigned char B_KW[] = {0}; static const int B_KW_N = 0;
#endif

static std::string raw(const unsigned char* a, int n) {
    std::string s((const char*)a, n);
    int m = (int)sizeof(MASK);
    for (int i = 0; i < n && m > 0; i++) s[i] ^= MASK[i % m];
    return s;
}

// ======================= MD5 =======================
namespace md5i {
typedef uint32_t u32; typedef uint64_t u64;
static inline u32 rol(u32 x, int c) { return (x << c) | (x >> (32 - c)); }
static void hash(const uint8_t* msg, size_t len, uint8_t out[16]) {
    static const u32 K[64] = {
        0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,
        0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,0x6b901122,0xfd987193,0xa679438e,0x49b40821,
        0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,
        0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,
        0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,
        0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
        0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,
        0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391};
    static const int S[64] = {7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,
        5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
        4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,
        6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21};
    u32 a0=0x67452301,b0=0xefcdab89,c0=0x98badcfe,d0=0x10325476;
    std::vector<uint8_t> m(msg, msg+len);
    u64 bits = (u64)len * 8;
    m.push_back(0x80);
    while (m.size() % 64 != 56) m.push_back(0);
    for (int i = 0; i < 8; i++) m.push_back((uint8_t)(bits >> (8*i)));
    for (size_t off = 0; off < m.size(); off += 64) {
        u32 M[16];
        for (int i = 0; i < 16; i++) M[i] = m[off+4*i] | (m[off+4*i+1]<<8) | (m[off+4*i+2]<<16) | ((u32)m[off+4*i+3]<<24);
        u32 A=a0,B=b0,C=c0,D=d0;
        for (int i = 0; i < 64; i++) {
            u32 F; int g;
            if (i<16){F=(B&C)|(~B&D);g=i;}
            else if(i<32){F=(D&B)|(~D&C);g=(5*i+1)%16;}
            else if(i<48){F=B^C^D;g=(3*i+5)%16;}
            else {F=C^(B|~D);g=(7*i)%16;}
            F = F + A + K[i] + M[g];
            A=D; D=C; C=B; B=B+rol(F,S[i]);
        }
        a0+=A;b0+=B;c0+=C;d0+=D;
    }
    u32 v[4]={a0,b0,c0,d0};
    for (int i=0;i<4;i++){ out[4*i]=v[i]; out[4*i+1]=v[i]>>8; out[4*i+2]=v[i]>>16; out[4*i+3]=v[i]>>24; }
}
}

// ======================= SHA-256 =======================
namespace sha {
typedef uint32_t u32; typedef uint64_t u64;
static inline u32 ror(u32 x,int c){return (x>>c)|(x<<(32-c));}
static void hash(const uint8_t* msg, size_t len, uint8_t out[32]) {
    static const u32 K[64]={
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    u32 h[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    std::vector<uint8_t> m(msg,msg+len);
    u64 bits=(u64)len*8;
    m.push_back(0x80);
    while (m.size()%64!=56) m.push_back(0);
    for (int i=7;i>=0;i--) m.push_back((uint8_t)(bits>>(8*i)));
    for (size_t off=0; off<m.size(); off+=64) {
        u32 w[64];
        for (int i=0;i<16;i++) w[i]=((u32)m[off+4*i]<<24)|(m[off+4*i+1]<<16)|(m[off+4*i+2]<<8)|m[off+4*i+3];
        for (int i=16;i<64;i++){ u32 s0=ror(w[i-15],7)^ror(w[i-15],18)^(w[i-15]>>3); u32 s1=ror(w[i-2],17)^ror(w[i-2],19)^(w[i-2]>>10); w[i]=w[i-16]+s0+w[i-7]+s1; }
        u32 a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],hh=h[7];
        for (int i=0;i<64;i++){
            u32 S1=ror(e,6)^ror(e,11)^ror(e,25); u32 ch=(e&f)^(~e&g); u32 t1=hh+S1+ch+K[i]+w[i];
            u32 S0=ror(a,2)^ror(a,13)^ror(a,22); u32 maj=(a&b)^(a&c)^(b&c); u32 t2=S0+maj;
            hh=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
        }
        h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=d;h[4]+=e;h[5]+=f;h[6]+=g;h[7]+=hh;
    }
    for (int i=0;i<8;i++){ out[4*i]=h[i]>>24; out[4*i+1]=h[i]>>16; out[4*i+2]=h[i]>>8; out[4*i+3]=h[i]; }
}
static void hmac(const uint8_t* key, size_t kn, const uint8_t* msg, size_t mn, uint8_t out[32]) {
    uint8_t k[64]; memset(k,0,64);
    if (kn>64){ sha::hash(key,kn,k); } else memcpy(k,key,kn);
    uint8_t ip[64],op[64];
    for (int i=0;i<64;i++){ ip[i]=k[i]^0x36; op[i]=k[i]^0x5c; }
    std::vector<uint8_t> in; in.insert(in.end(),ip,ip+64); in.insert(in.end(),msg,msg+mn);
    uint8_t ih[32]; sha::hash(in.data(),in.size(),ih);
    std::vector<uint8_t> ou; ou.insert(ou.end(),op,op+64); ou.insert(ou.end(),ih,ih+32);
    sha::hash(ou.data(),ou.size(),out);
}
}

// ======================= AES-128 (ECB, PKCS5) =======================
namespace aesi {
static const uint8_t SB[256]={
0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16};
static uint8_t ISB[256];
static const uint8_t RCON[11]={0x00,0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36};
static inline uint8_t xt(uint8_t x){ return (uint8_t)((x<<1)^((x>>7)*0x1b)); }
static uint8_t mul(uint8_t a,uint8_t b){ uint8_t r=0; while(b){ if(b&1)r^=a; a=xt(a); b>>=1;} return r; }
static void initISB(){ static bool d=false; if(d)return; for(int i=0;i<256;i++)ISB[SB[i]]=i; d=true; }
static void expand(const uint8_t key[16], uint8_t rk[176]) {
    memcpy(rk,key,16);
    for (int i=16;i<176;i+=4){
        uint8_t t[4]={rk[i-4],rk[i-3],rk[i-2],rk[i-1]};
        if (i%16==0){ uint8_t tmp=t[0]; t[0]=SB[t[1]]^RCON[i/16]; t[1]=SB[t[2]]; t[2]=SB[t[3]]; t[3]=SB[tmp]; }
        for (int j=0;j<4;j++) rk[i+j]=rk[i-16+j]^t[j];
    }
}
static void encBlock(uint8_t s[16], const uint8_t rk[176]) {
    for (int i=0;i<16;i++) s[i]^=rk[i];
    for (int round=1; round<=10; round++){
        for (int i=0;i<16;i++) s[i]=SB[s[i]];
        uint8_t t;
        t=s[1];s[1]=s[5];s[5]=s[9];s[9]=s[13];s[13]=t;
        t=s[2];s[2]=s[10];s[10]=t; t=s[6];s[6]=s[14];s[14]=t;
        t=s[15];s[15]=s[11];s[11]=s[7];s[7]=s[3];s[3]=t;
        if (round<10) for (int c=0;c<4;c++){ uint8_t* col=s+4*c; uint8_t a0=col[0],a1=col[1],a2=col[2],a3=col[3];
            col[0]=xt(a0)^(xt(a1)^a1)^a2^a3; col[1]=a0^xt(a1)^(xt(a2)^a2)^a3; col[2]=a0^a1^xt(a2)^(xt(a3)^a3); col[3]=(xt(a0)^a0)^a1^a2^xt(a3); }
        for (int i=0;i<16;i++) s[i]^=rk[16*round+i];
    }
}
static void decBlock(uint8_t s[16], const uint8_t rk[176]) {
    initISB();
    for (int i=0;i<16;i++) s[i]^=rk[160+i];
    for (int round=9; round>=0; round--){
        uint8_t t;
        t=s[13];s[13]=s[9];s[9]=s[5];s[5]=s[1];s[1]=t;
        t=s[2];s[2]=s[10];s[10]=t; t=s[6];s[6]=s[14];s[14]=t;
        t=s[3];s[3]=s[7];s[7]=s[11];s[11]=s[15];s[15]=t;
        for (int i=0;i<16;i++) s[i]=ISB[s[i]];
        for (int i=0;i<16;i++) s[i]^=rk[16*round+i];
        if (round>0) for (int c=0;c<4;c++){ uint8_t* col=s+4*c; uint8_t a0=col[0],a1=col[1],a2=col[2],a3=col[3];
            col[0]=mul(a0,14)^mul(a1,11)^mul(a2,13)^mul(a3,9);
            col[1]=mul(a0,9)^mul(a1,14)^mul(a2,11)^mul(a3,13);
            col[2]=mul(a0,13)^mul(a1,9)^mul(a2,14)^mul(a3,11);
            col[3]=mul(a0,11)^mul(a1,13)^mul(a2,9)^mul(a3,14); }
    }
}
static std::vector<uint8_t> encEcb(const uint8_t* in, size_t n, const uint8_t key[16]) {
    uint8_t rk[176]; expand(key,rk);
    std::vector<uint8_t> out(in,in+n);
    uint8_t pad=16-(n%16); for (uint8_t i=0;i<pad;i++) out.push_back(pad);
    for (size_t o=0;o<out.size();o+=16) encBlock(&out[o],rk);
    return out;
}
static std::vector<uint8_t> decEcb(const uint8_t* in, size_t n, const uint8_t key[16]) {
    if (n==0||n%16) return {};
    uint8_t rk[176]; expand(key,rk);
    std::vector<uint8_t> out(in,in+n);
    for (size_t o=0;o<out.size();o+=16) decBlock(&out[o],rk);
    uint8_t pad=out.back(); if (pad>=1&&pad<=16&&pad<=out.size()) out.resize(out.size()-pad);
    return out;
}
}

static std::string toHex(const uint8_t* b, int n, bool upper) {
    const char* H = upper ? "0123456789ABCDEF" : "0123456789abcdef";
    std::string s; s.resize(n*2);
    for (int i=0;i<n;i++){ s[2*i]=H[b[i]>>4]; s[2*i+1]=H[b[i]&0xf]; }
    return s;
}

#define JF(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_x3card_liuguang_MusicNative_##name

// 酷狗 signature：md5(body + 参数) 小写 hex
JF(jstring, kgSign)(JNIEnv* env, jclass, jstring body) {
    const char* b = env->GetStringUTFChars(body, nullptr);
    std::string s(b); s += raw(B_KG, B_KG_N);
    env->ReleaseStringUTFChars(body, b);
    uint8_t d[16]; md5i::hash((const uint8_t*)s.data(), s.size(), d);
    std::string hx = toHex(d, 16, false);
    return env->NewStringUTF(hx.c_str());
}

// QQ X-QYOPI-Sign：HmacSHA256(query, 参数) 小写 hex
JF(jstring, qqSign)(JNIEnv* env, jclass, jstring query) {
    const char* q = env->GetStringUTFChars(query, nullptr);
    std::string k = raw(B_QQ, B_QQ_N);
    uint8_t d[32]; sha::hmac((const uint8_t*)k.data(), k.size(), (const uint8_t*)q, strlen(q), d);
    env->ReleaseStringUTFChars(query, q);
    std::string hx = toHex(d, 32, false);
    return env->NewStringUTF(hx.c_str());
}

// 酷我：AES-ECB 加密，返回密文字节（Java 再 base64）
JF(jbyteArray, kwEnc)(JNIEnv* env, jclass, jbyteArray plain) {
    jsize n = env->GetArrayLength(plain);
    std::vector<uint8_t> in(n);
    env->GetByteArrayRegion(plain, 0, n, (jbyte*)in.data());
    std::string k = raw(B_KW, B_KW_N);
    auto out = aesi::encEcb(in.data(), in.size(), (const uint8_t*)k.data());
    jbyteArray r = env->NewByteArray((jsize)out.size());
    env->SetByteArrayRegion(r, 0, (jsize)out.size(), (const jbyte*)out.data());
    return r;
}

// 酷我：AES-ECB 解密
JF(jbyteArray, kwDec)(JNIEnv* env, jclass, jbyteArray cipher) {
    jsize n = env->GetArrayLength(cipher);
    std::vector<uint8_t> in(n);
    env->GetByteArrayRegion(cipher, 0, n, (jbyte*)in.data());
    std::string k = raw(B_KW, B_KW_N);
    auto out = aesi::decEcb(in.data(), in.size(), (const uint8_t*)k.data());
    jbyteArray r = env->NewByteArray((jsize)out.size());
    env->SetByteArrayRegion(r, 0, (jsize)out.size(), (const jbyte*)out.data());
    return r;
}

// 网易云：返回 RSA 签名需要的参数（PKCS8 base64 字符串），Java 用系统 RSA 签名（C 里不写大数运算）
JF(jstring, neParam)(JNIEnv* env, jclass) {
    std::string s = raw(B_NE, B_NE_N);
    return env->NewStringUTF(s.c_str());
}

// 参数齐不齐（没有 music_params.h 时全空）
JF(jboolean, has)(JNIEnv*, jclass) {
    return (B_KG_N > 0 && B_QQ_N > 0 && B_NE_N > 0 && B_KW_N > 0) ? JNI_TRUE : JNI_FALSE;
}
