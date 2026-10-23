#version 460

layout(binding = 0, std140) uniform Scene {
    mat4 mvp;
    float time;
};
struct Segment { vec4 originHeight; vec4 colorRadius; };
layout(binding = 1, std430) readonly buffer Beams { Segment beams[]; };
layout(location = 0) out vec3 color;
layout(location = 1) out vec2 uv;

void main() {
#ifdef VOXY_VULKAN
    int vertex = gl_VertexIndex;
    Segment beam = beams[gl_InstanceIndex];
#else
    int vertex = gl_VertexID;
    Segment beam = beams[gl_InstanceID];
#endif
    const ivec2 quad[6] = ivec2[6](ivec2(0,0), ivec2(1,0), ivec2(1,1), ivec2(0,0), ivec2(1,1), ivec2(0,1));
    ivec2 corner = quad[vertex % 6];
    float angle = float(vertex / 6 + corner.x) * 1.570796327 + time * 0.157079633;
    vec3 offset = vec3(cos(angle) * beam.colorRadius.w, float(corner.y) * beam.originHeight.w, sin(angle) * beam.colorRadius.w);
    gl_Position = mvp * vec4(beam.originHeight.xyz + offset, 1);
    color = beam.colorRadius.rgb;
    uv = vec2(corner.x, offset.y * 0.5 - time * 0.2);
}
