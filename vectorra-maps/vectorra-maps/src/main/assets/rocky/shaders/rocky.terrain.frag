#version 460
#pragma import_defines(ROCKY_ATMOSPHERE)

layout(push_constant) uniform PushConstants {
    mat4 projection;
    mat4 modelview;
} pc;

// inter-stage interface block
layout(location = 0) in Varyings {
    vec2 uv;
    vec3 normalVs;
    vec3 vertexVs;
    vec3 lookWs;
    vec3 cameraWs;
    vec3 sunDirEcef;
    float discardVert;
} vary;

// uniforms (TerrainState.h)
layout(set = 0, binding = 9) uniform TerrainSettings {
    vec4 backgroundColor;
    float atmosphere;
    float lighting;
    float debugTriangles;
    float debugNormals;
} u_terrain;

layout(set = 0, binding = 11) uniform sampler2D u_colorTex;

#pragma include "rocky.viewdependentstate.glsl"
#pragma include "rocky.lighting.glsl"
#pragma include "rocky.atmo.glsl"
#pragma include "rocky.debug.frag.glsl"

// outputs
layout(location = 0) out vec4 outColor;

void main()
{
    if (vary.discardVert > 0.0)
        discard;

    // sample the imagery color
    vec4 texel = texture(u_colorTex, vary.uv);

    // mix in the background color
    outColor = mix(u_terrain.backgroundColor, clamp(texel, 0, 1), texel.a);

    vec3 normalVs = normalize(vary.normalVs);

    // debug normals
    outColor.rgb = mix(outColor.rgb, (normalVs + 1.0) * 0.5, u_terrain.debugNormals);

#if defined(ROCKY_ATMOSPHERE)
    vec3 ground_color = applyAtmoColorToGround(
        outColor.rgb, vary.vertexVs, vary.lookWs,
        vary.cameraWs, normalize(vary.sunDirEcef), u_vds.ellipsoidAxes);

    outColor.rgb = mix(outColor.rgb, ground_color, u_terrain.lighting * u_terrain.atmosphere);
#endif

    // PBR lighting
    vec4 lit_color = applyLighting(outColor, vary.vertexVs, normalVs);
    outColor = mix(outColor, lit_color, u_terrain.lighting);

    // show triangle outlines
    applyDebugTriangles(outColor, u_terrain.debugTriangles);
}
