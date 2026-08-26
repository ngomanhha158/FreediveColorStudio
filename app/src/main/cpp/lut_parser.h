// ============================================================================
//  FABLE TASK 1.4 — C++ LUT PARSER (.cube) · Android NDK
//  Doc file Adobe .cube (33x33x33 / 65x65x65), dong goi RGBA float phang
//  san sang upload len VkImage 3D (VK_FORMAT_R32G32B32A32_SFLOAT, hoac
//  convert fp16 -> R16G16B16A16_SFLOAT de tiet kiem 1/2 VRAM).
// ============================================================================
#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace fdc {

struct CubeLut {
    int                 size = 0;        // N cua luoi N^3 (33 hoac 65)
    std::string         title;           // dong TITLE trong file (neu co)
    float               domain_min[3] = {0.f, 0.f, 0.f};
    float               domain_max[3] = {1.f, 1.f, 1.f};
    // RGBA xen ke, alpha = 1.0; thu tu Adobe chuan: RED chay nhanh nhat
    // (index = r + g*N + b*N*N), khop truc tiep voi toa do sampler3D.
    std::vector<float>  rgba;            // size = N*N*N*4

    bool   valid() const { return size >= 2 && rgba.size() == size_t(size)*size*size*4; }
    size_t byteSize() const { return rgba.size() * sizeof(float); }
};

// Parse noi dung file .cube da doc vao bo nho (tu AAsset hoac ifstream).
// Tra ve CubeLut.valid() == false neu file sai dinh dang; chi tiet loi ghi
// vao outError (neu khac nullptr). Chap nhan moi N trong [2..129], canh bao
// qua outError == nullptr bo qua neu N khac 33/65.
CubeLut ParseCubeLut(const char* data, size_t len, std::string* outError = nullptr);

// Tien ich: doc tu duong dan he thong (bo nho trong). Voi Android Assets,
// doc AAsset vao vector<char> roi goi ParseCubeLut truc tiep.
CubeLut LoadCubeLutFromFile(const std::string& path, std::string* outError = nullptr);

}  // namespace fdc
