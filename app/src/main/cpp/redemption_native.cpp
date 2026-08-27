// redemption_native.cpp — Native redemption code validator.
//
// Validates offline redemption codes issued by RedemptionCodeValidator.kt.
// Code format:  BASE64(payloadJson) + "." + BASE64(HMAC-SHA256(BASE64(payloadJson), secretKey))
//
// The native layer performs the cryptographic heavy lifting (HMAC-SHA256 over
// the base64 payload, constant-time signature compare) and the expiry check.
// JSON is NOT parsed by a full parser — only the `expiresAt` long field is
// extracted via a minimal scanner, which is enough to decide expiry without
// pulling a JSON dependency into the NDK build.
//
// JNI entry point:
//   Java_com_lxseek_chat_membership_RedemptionNativeBridge_validateCode
//   (JNIEnv*, jobject, jstring code, jbyteArray secretKey) -> jint
//     0 = valid
//     1 = invalid (malformed / base64 error / signature mismatch / bad payload)
//     2 = expired (signature valid but expiresAt <= now)
//
// No external libraries are used; SHA-256 and HMAC are implemented inline.

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <chrono>

// ──────────────────────────────────────────────────────────────────────────
// SHA-256 (FIPS 180-4), minimal self-contained implementation.
// ──────────────────────────────────────────────────────────────────────────
namespace {

constexpr uint32_t K_SHA256[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
    0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
    0xd807aa98u, 0x12835bafu, 0x243185beu, 0x550c7dc3u,
    0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
    0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
    0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa83166dau, 0xb00327c8u, 0xbf597fc7u,
    0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
    0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
    0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
    0xd192e819u, 0xd6990623u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
    0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
    0x748f6ee6u, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
};

inline uint32_t rotr32(uint32_t x, uint32_t n) {
    return (x >> n) | (x << (32u - n));
}

struct Sha256Ctx {
    uint32_t state[8];
    uint64_t bitlen;
    uint8_t buffer[64];
    size_t buflen;
};

void sha256_init(Sha256Ctx& ctx) {
    ctx.state[0] = 0x6a09e667u;
    ctx.state[1] = 0xbb67ae85u;
    ctx.state[2] = 0x3c6ef372u;
    ctx.state[3] = 0xa54ff53au;
    ctx.state[4] = 0x510e527fu;
    ctx.state[5] = 0x9b05688cu;
    ctx.state[6] = 0x1f83d9abu;
    ctx.state[7] = 0x5be0cd19u;
    ctx.bitlen = 0;
    ctx.buflen = 0;
}

void sha256_transform(Sha256Ctx& ctx, const uint8_t block[64]) {
    uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
        w[i] = (uint32_t(block[i * 4]) << 24) |
               (uint32_t(block[i * 4 + 1]) << 16) |
               (uint32_t(block[i * 4 + 2]) << 8) |
               (uint32_t(block[i * 4 + 3]));
    }
    for (int i = 16; i < 64; ++i) {
        uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = ctx.state[0], b = ctx.state[1], c = ctx.state[2], d = ctx.state[3];
    uint32_t e = ctx.state[4], f = ctx.state[5], g = ctx.state[6], h = ctx.state[7];
    for (int i = 0; i < 64; ++i) {
        uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t temp1 = h + S1 + ch + K_SHA256[i] + w[i];
        uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = S0 + maj;
        h = g; g = f; f = e; e = d + temp1;
        d = c; c = b; b = a; a = temp1 + temp2;
    }
    ctx.state[0] += a; ctx.state[1] += b; ctx.state[2] += c; ctx.state[3] += d;
    ctx.state[4] += e; ctx.state[5] += f; ctx.state[6] += g; ctx.state[7] += h;
}

void sha256_update(Sha256Ctx& ctx, const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; ++i) {
        ctx.buffer[ctx.buflen++] = data[i];
        if (ctx.buflen == 64) {
            sha256_transform(ctx, ctx.buffer);
            ctx.bitlen += 512;
            ctx.buflen = 0;
        }
    }
}

void sha256_final(Sha256Ctx& ctx, uint8_t out[32]) {
    uint64_t bitlen = ctx.bitlen + (uint64_t)ctx.buflen * 8;
    // Append 0x80.
    ctx.buffer[ctx.buflen++] = 0x80;
    if (ctx.buflen > 56) {
        while (ctx.buflen < 64) ctx.buffer[ctx.buflen++] = 0x00;
        sha256_transform(ctx, ctx.buffer);
        ctx.buflen = 0;
    }
    while (ctx.buflen < 56) ctx.buffer[ctx.buflen++] = 0x00;
    // Append length in big-endian.
    for (int i = 7; i >= 0; --i) {
        ctx.buffer[ctx.buflen++] = (uint8_t)((bitlen >> (i * 8)) & 0xff);
    }
    sha256_transform(ctx, ctx.buffer);
    for (int i = 0; i < 8; ++i) {
        out[i * 4]     = (uint8_t)((ctx.state[i] >> 24) & 0xff);
        out[i * 4 + 1] = (uint8_t)((ctx.state[i] >> 16) & 0xff);
        out[i * 4 + 2] = (uint8_t)((ctx.state[i] >> 8) & 0xff);
        out[i * 4 + 3] = (uint8_t)(ctx.state[i] & 0xff);
    }
}

void sha256(const uint8_t* data, size_t len, uint8_t out[32]) {
    Sha256Ctx ctx;
    sha256_init(ctx);
    sha256_update(ctx, data, len);
    sha256_final(ctx, out);
}

// ──────────────────────────────────────────────────────────────────────────
// HMAC-SHA256 (RFC 2104). Output is 32 bytes.
// ──────────────────────────────────────────────────────────────────────────
void hmacSha256(const uint8_t* key, size_t keyLen,
                const uint8_t* msg, size_t msgLen,
                uint8_t out[32]) {
    uint8_t k[64];
    if (keyLen > 64) {
        sha256(key, keyLen, k);
        std::memset(k + 32, 0, 32);
    } else {
        std::memcpy(k, key, keyLen);
        std::memset(k + keyLen, 0, 64 - keyLen);
    }
    uint8_t ipad[64];
    uint8_t opad[64];
    for (int i = 0; i < 64; ++i) {
        ipad[i] = k[i] ^ 0x36;
        opad[i] = k[i] ^ 0x5c;
    }
    // inner = SHA256(ipad || msg)
    Sha256Ctx inner;
    sha256_init(inner);
    sha256_update(inner, ipad, 64);
    sha256_update(inner, msg, msgLen);
    uint8_t innerHash[32];
    sha256_final(inner, innerHash);
    // outer = SHA256(opad || inner)
    Sha256Ctx outer;
    sha256_init(outer);
    sha256_update(outer, opad, 64);
    sha256_update(outer, innerHash, 32);
    sha256_final(outer, out);
}

// ──────────────────────────────────────────────────────────────────────────
// Base64 (RFC 4648) decoder. Returns false on invalid input.
// ──────────────────────────────────────────────────────────────────────────
bool base64Decode(const std::string& in, std::vector<uint8_t>& out) {
    static const int8_t dec[256] = []() {
        int8_t d[256];
        std::memset(d, -1, sizeof(d));
        const char* tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < 64; ++i) d[(uint8_t)tbl[i]] = (int8_t)i;
        return *reinterpret_cast<int8_t(*)[256]>(d);
    }();
    out.clear();
    out.reserve((in.size() / 4) * 3);
    uint32_t buf = 0;
    int bits = 0;
    for (char c : in) {
        if (c == '=') break;
        int8_t v = dec[(uint8_t)c];
        if (v < 0) return false; // invalid character
        buf = (buf << 6) | (uint32_t)v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back((uint8_t)((buf >> bits) & 0xff));
        }
    }
    return true;
}

// ──────────────────────────────────────────────────────────────────────────
// Constant-time comparison.
// ──────────────────────────────────────────────────────────────────────────
bool constantTimeEquals(const uint8_t* a, const uint8_t* b, size_t len) {
    uint8_t diff = 0;
    for (size_t i = 0; i < len; ++i) diff |= a[i] ^ b[i];
    return diff == 0;
}

// ──────────────────────────────────────────────────────────────────────────
// Minimal JSON long-field extractor.
//
// Scans for `"field":<optional whitespace><integer>` and writes the parsed
// long into `out`. Handles optional leading '-'. This is NOT a general JSON
// parser — it only needs to find `expiresAt` in payloads produced by
// RedemptionCodeValidator.issue(), which uses kotlinx.serialization with
// deterministic field ordering. Returns false if the field is absent or the
// value is not a parseable integer.
// ──────────────────────────────────────────────────────────────────────────
bool extractJsonLong(const std::string& json, const std::string& field, int64_t& out) {
    std::string needle = "\"" + field + "\"";
    size_t pos = json.find(needle);
    if (pos == std::string::npos) return false;
    pos += needle.size();
    // Skip whitespace and a single ':'.
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t' || json[pos] == '\n' || json[pos] == '\r')) ++pos;
    if (pos >= json.size() || json[pos] != ':') return false;
    ++pos;
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t' || json[pos] == '\n' || json[pos] == '\r')) ++pos;
    if (pos >= json.size()) return false;
    bool negative = false;
    if (json[pos] == '-') { negative = true; ++pos; }
    if (pos >= json.size() || json[pos] < '0' || json[pos] > '9') return false;
    int64_t val = 0;
    while (pos < json.size() && json[pos] >= '0' && json[pos] <= '9') {
        val = val * 10 + (int64_t)(json[pos] - '0');
        ++pos;
    }
    out = negative ? -val : val;
    return true;
}

// Wall-clock milliseconds since Unix epoch.
int64_t nowMillis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

// Result codes — kept in sync with RedemptionNativeBridge.kt.
constexpr int RESULT_VALID = 0;
constexpr int RESULT_INVALID = 1;
constexpr int RESULT_EXPIRED = 2;

} // namespace

// ──────────────────────────────────────────────────────────────────────────
// JNI entry point.
// ──────────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jint JNICALL
Java_com_lxseek_chat_membership_RedemptionNativeBridge_validateCode(
        JNIEnv* env, jobject /* thiz */, jstring jCode, jbyteArray jSecretKey) {
    if (jCode == nullptr || jSecretKey == nullptr) return RESULT_INVALID;

    const char* codeChars = env->GetStringUTFChars(jCode, nullptr);
    if (codeChars == nullptr) return RESULT_INVALID; // OOM
    std::string code(codeChars);
    env->ReleaseStringUTFChars(jCode, codeChars);

    // Trim leading/trailing whitespace.
    size_t start = code.find_first_not_of(" \t\r\n");
    size_t end = code.find_last_not_of(" \t\r\n");
    if (start == std::string::npos) return RESULT_INVALID;
    code = code.substr(start, end - start + 1);

    // Split payload.signature on the FIRST '.'. The base64 payload itself
    // never contains '.', so a single split is unambiguous.
    size_t dot = code.find('.');
    if (dot == std::string::npos || dot == 0 || dot == code.size() - 1) {
        return RESULT_INVALID;
    }
    std::string base64Payload = code.substr(0, dot);
    std::string base64Signature = code.substr(dot + 1);

    // Read secret key bytes.
    jsize keyLen = env->GetArrayLength(jSecretKey);
    if (keyLen <= 0) return RESULT_INVALID;
    std::vector<uint8_t> secretKey(keyLen);
    env->GetByteArrayRegion(jSecretKey, 0, keyLen,
                            reinterpret_cast<jbyte*>(secretKey.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return RESULT_INVALID;
    }

    // Decode provided signature.
    std::vector<uint8_t> providedSig;
    if (!base64Decode(base64Signature, providedSig) || providedSig.size() != 32) {
        return RESULT_INVALID;
    }

    // Compute expected HMAC-SHA256 over the ASCII bytes of base64Payload.
    std::vector<uint8_t> payloadAscii(base64Payload.begin(), base64Payload.end());
    uint8_t expectedSig[32];
    hmacSha256(secretKey.data(), secretKey.size(),
               payloadAscii.data(), payloadAscii.size(), expectedSig);

    // Constant-time signature compare.
    if (!constantTimeEquals(expectedSig, providedSig.data(), 32)) {
        return RESULT_INVALID;
    }

    // Signature valid — decode payload and check expiry.
    std::vector<uint8_t> payloadBytes;
    if (!base64Decode(base64Payload, payloadBytes)) {
        return RESULT_INVALID;
    }
    std::string payloadJson(payloadBytes.begin(), payloadBytes.end());

    int64_t expiresAt = 0;
    if (!extractJsonLong(payloadJson, "expiresAt", expiresAt)) {
        return RESULT_INVALID;
    }

    if (nowMillis() >= expiresAt) {
        return RESULT_EXPIRED;
    }
    return RESULT_VALID;
}