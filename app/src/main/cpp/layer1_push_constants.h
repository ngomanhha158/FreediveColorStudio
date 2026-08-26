// ============================================================================
//  TASK 1.3 + 2.1 — C++ INTEGRATION: push constants Layer 1 day du
//  KHOP TUNG BYTE voi block Layer1PC trong color_space.frag (48 byte).
// ============================================================================
#pragma once
#include <cstring>
#include <vulkan/vulkan.h>
#include "color_pipeline.h"   // fdc::kPresets, fdc::PreLutParams

namespace fdc {

struct alignas(16) Layer1PushConstants {
    float temperature;
    float tint;
    float exposureEv;
    float contrast;
    float shadowsLift;
    float highlights;
    float redRecovery;
    float antiGreen;
    float magentaGuard;
    float layerOn;        // eye-toggle Layer 1 (1.0 = bat)
    float _pad0 = 0.f;
    float _pad1 = 0.f;
};
static_assert(sizeof(Layer1PushConstants) == 48, "Layer1PC phai 48 byte");

// Dien push constants tu preset + slider toan cuc
inline Layer1PushConstants MakeLayer1PC(const ColorPreset& p, float antiGreen, bool layerOn) {
    return Layer1PushConstants{
        p.pre_lut.white_balance_temperature,
        p.pre_lut.tint,
        p.pre_lut.exposure_ev,
        p.pre_lut.contrast,
        p.pre_lut.shadows_lift,
        p.pre_lut.highlights,
        p.pre_lut.red_channel_recovery,
        antiGreen,
        p.pre_lut.magenta_shadow_guard ? 1.f : 0.f,
        layerOn ? 1.f : 0.f,
        0.f, 0.f,
    };
}

// Dien tu bo tham so nguoi dung chinh tay (UI slider Task 2.3 ghi de preset)
inline Layer1PushConstants MakeLayer1PC(const PreLutParams& p, float antiGreen, bool layerOn) {
    return Layer1PushConstants{
        p.white_balance_temperature, p.tint, p.exposure_ev, p.contrast,
        p.shadows_lift, p.highlights, p.red_channel_recovery,
        antiGreen, p.magenta_shadow_guard ? 1.f : 0.f, layerOn ? 1.f : 0.f,
        0.f, 0.f,
    };
}

// ----------------------------------------------------------------------------
// Khai bao range: { VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(Layer1PushConstants) }
// Day moi frame: vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_FRAGMENT_BIT,
//                                   0, sizeof(pc), &pc);
// Keyframing (Tuan 3): dien tu fdc::Evaluate(k0,k1,t).pre_lut qua overload thu 2.
// ----------------------------------------------------------------------------

}  // namespace fdc
