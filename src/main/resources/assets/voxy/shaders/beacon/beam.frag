#version 460

layout(location = 0) in vec3 color;
layout(location = 1) in vec2 uv;
layout(location = 0) out vec4 outColor;

void main() {
    // Fullbright core; depth is composited with the LoDs, not Minecraft's short-range fog.
    float pulse = 0.85 + 0.15 * cos(6.283185307 * (uv.y + uv.x));
    outColor = vec4(color * pulse, 1);
}
