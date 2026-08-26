// ============================================================================
//  FABLE TASK 1.4 — C++ LUT PARSER (.cube) · trien khai
//  Dinh dang Adobe Cube LUT Specification 1.0: dong "#" la comment;
//  keyword TITLE / DOMAIN_MIN / DOMAIN_MAX / LUT_3D_SIZE; sau do N^3 dong
//  "r g b" (float), RED chay nhanh nhat.
// ============================================================================
#include "lut_parser.h"

#include <cctype>
#include <cstdlib>
#include <fstream>

namespace fdc {
namespace {

// Con tro chay tren buffer text — tranh sstream de giam cap phat bo nho
struct Cursor {
    const char* p;
    const char* end;
    bool eof() const { return p >= end; }
};

void skipSpaces(Cursor& c) { while (!c.eof() && (*c.p == ' ' || *c.p == '\t' || *c.p == '\r')) ++c.p; }

// Doc 1 dong (bo qua dong trong / comment), tra ve [begin,end) cua dong
bool nextLine(Cursor& c, const char*& lb, const char*& le) {
    while (!c.eof()) {
        skipSpaces(c);
        const char* start = c.p;
        while (!c.eof() && *c.p != '\n') ++c.p;
        const char* stop = c.p;
        if (!c.eof()) ++c.p;                                   // nuot '\n'
        while (stop > start && (stop[-1] == '\r' || stop[-1] == ' ')) --stop;
        if (stop == start) continue;                            // dong trong
        if (*start == '#') continue;                            // comment
        lb = start; le = stop;
        return true;
    }
    return false;
}

bool startsWith(const char* lb, const char* le, const char* kw) {
    while (*kw) { if (lb >= le || *lb != *kw) return false; ++lb; ++kw; }
    return lb >= le || *lb == ' ' || *lb == '\t';
}

// strtof gioi han trong [lb, le)
bool parseFloats(const char* lb, const char* le, float* out, int count) {
    char* endp = nullptr;
    for (int i = 0; i < count; ++i) {
        out[i] = std::strtof(lb, &endp);
        if (endp == lb || endp > le) return false;
        lb = endp;
    }
    return true;
}

}  // namespace

CubeLut ParseCubeLut(const char* data, size_t len, std::string* outError) {
    CubeLut lut;
    auto fail = [&](const char* msg) {
        if (outError) *outError = msg;
        lut.rgba.clear(); lut.size = 0;
        return lut;
    };
    if (!data || len == 0) return fail("File .cube rong");

    Cursor cur{data, data + len};
    const char *lb, *le;
    size_t entryIdx = 0, expected = 0;

    while (nextLine(cur, lb, le)) {
        if (startsWith(lb, le, "TITLE")) {
            const char* q1 = lb; while (q1 < le && *q1 != '"') ++q1;
            const char* q2 = le; if (q1 < le) { q2 = q1 + 1; while (q2 < le && *q2 != '"') ++q2; }
            if (q1 < le && q2 <= le) lut.title.assign(q1 + 1, q2);
        } else if (startsWith(lb, le, "DOMAIN_MIN")) {
            if (!parseFloats(lb + 10, le, lut.domain_min, 3)) return fail("DOMAIN_MIN sai dinh dang");
        } else if (startsWith(lb, le, "DOMAIN_MAX")) {
            if (!parseFloats(lb + 10, le, lut.domain_max, 3)) return fail("DOMAIN_MAX sai dinh dang");
        } else if (startsWith(lb, le, "LUT_3D_SIZE")) {
            float n = 0.f;
            if (!parseFloats(lb + 11, le, &n, 1)) return fail("LUT_3D_SIZE sai dinh dang");
            lut.size = static_cast<int>(n);
            if (lut.size < 2 || lut.size > 129) return fail("LUT_3D_SIZE ngoai khoang [2..129]");
            // Ho tro chinh thuc 33 va 65 (spec du an); size khac van parse duoc
            expected = size_t(lut.size) * lut.size * lut.size;
            lut.rgba.reserve(expected * 4);
        } else if (startsWith(lb, le, "LUT_1D_SIZE")) {
            return fail("File la LUT 1D — app chi ho tro LUT 3D");
        } else {
            // Dong du lieu "r g b"
            if (expected == 0) return fail("Gap du lieu truoc khi co LUT_3D_SIZE");
            if (entryIdx >= expected) return fail("Thua dong du lieu so voi N^3");
            float rgb[3];
            if (!parseFloats(lb, le, rgb, 3)) return fail("Dong du lieu RGB sai dinh dang");
            lut.rgba.push_back(rgb[0]);
            lut.rgba.push_back(rgb[1]);
            lut.rgba.push_back(rgb[2]);
            lut.rgba.push_back(1.0f);          // alpha pad — can le RGBA cho Vulkan
            ++entryIdx;
        }
    }
    if (expected == 0)          return fail("Thieu keyword LUT_3D_SIZE");
    if (entryIdx != expected)   return fail("Thieu dong du lieu: chua du N^3 muc");
    if (outError) outError->clear();
    return lut;
}

CubeLut LoadCubeLutFromFile(const std::string& path, std::string* outError) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) {
        if (outError) *outError = "Khong mo duoc file: " + path;
        return {};
    }
    const std::streamsize sz = f.tellg();
    f.seekg(0);
    std::vector<char> buf(static_cast<size_t>(sz));
    if (!f.read(buf.data(), sz)) {
        if (outError) *outError = "Loi doc file: " + path;
        return {};
    }
    return ParseCubeLut(buf.data(), buf.size(), outError);
}

}  // namespace fdc

// ----------------------------------------------------------------------------
// UPLOAD LEN VULKAN (tom tat — trien khai day du o vulkan_renderer.cpp Tuan 1):
//   1. Tao VkImage 3D: extent {N,N,N}, format VK_FORMAT_R32G32B32A32_SFLOAT
//      (hoac convert sang fp16 R16G16B16A16_SFLOAT de giam 1/2 VRAM).
//   2. Copy lut.rgba vao staging buffer -> vkCmdCopyBufferToImage.
//   3. Sampler: VK_FILTER_LINEAR khong can thiet — shader tu noi suy
//      TETRAHEDRAL bang texelFetch, chi can VK_FILTER_NEAREST + CLAMP_TO_EDGE.
// ----------------------------------------------------------------------------
