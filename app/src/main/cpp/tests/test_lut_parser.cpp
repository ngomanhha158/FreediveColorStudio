// Unit test nho cho lut_parser — chay tren host de xac thuc logic parse
#include "../lut_parser.h"
#include <cassert>
#include <cstdio>
#include <string>
int main() {
    // 1. LUT 2^3 identity hop le, co TITLE/DOMAIN/comment/dong trong
    std::string ok =
        "# Grade Co test\nTITLE \"Identity2\"\n\nDOMAIN_MIN 0.0 0.0 0.0\n"
        "DOMAIN_MAX 1.0 1.0 1.0\nLUT_3D_SIZE 2\n"
        "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n";
    std::string err;
    auto lut = fdc::ParseCubeLut(ok.data(), ok.size(), &err);
    assert(lut.valid() && lut.size == 2 && err.empty());
    assert(lut.title == "Identity2");
    assert(lut.rgba.size() == 8u * 4u);
    // RED chay nhanh nhat: entry[1] = (r=1,g=0,b=0) -> 1,0,0
    assert(lut.rgba[4] == 1.f && lut.rgba[5] == 0.f && lut.rgba[6] == 0.f && lut.rgba[7] == 1.f);
    // entry cuoi = 1,1,1
    assert(lut.rgba[7*4+0] == 1.f && lut.rgba[7*4+2] == 1.f);

    // 2. Thieu dong du lieu -> loi
    std::string bad1 = "LUT_3D_SIZE 2\n0 0 0\n";
    auto l1 = fdc::ParseCubeLut(bad1.data(), bad1.size(), &err);
    assert(!l1.valid() && !err.empty());

    // 3. Du lieu truoc LUT_3D_SIZE -> loi
    std::string bad2 = "0 0 0\nLUT_3D_SIZE 2\n";
    auto l2 = fdc::ParseCubeLut(bad2.data(), bad2.size(), &err);
    assert(!l2.valid());

    // 4. LUT 1D -> tu choi ro rang
    std::string bad3 = "LUT_1D_SIZE 1024\n";
    auto l3 = fdc::ParseCubeLut(bad3.data(), bad3.size(), &err);
    assert(!l3.valid() && err.find("1D") != std::string::npos);

    // 5. Sinh LUT 33^3 identity trong bo nho -> parse OK
    std::string big = "LUT_3D_SIZE 33\n";
    char buf[64];
    for (int b = 0; b < 33; b++) for (int g = 0; g < 33; g++) for (int r = 0; r < 33; r++) {
        snprintf(buf, sizeof buf, "%.6f %.6f %.6f\n", r/32.0, g/32.0, b/32.0);
        big += buf;
    }
    auto l5 = fdc::ParseCubeLut(big.data(), big.size(), &err);
    assert(l5.valid() && l5.size == 33 && l5.rgba.size() == 33u*33u*33u*4u);

    printf("TAT CA 5 TEST PASS\n");
    return 0;
}
