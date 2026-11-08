#version 450

layout(location = 0) in vec3 worldNormal;
layout(location = 1) in vec3 worldLongitudeTangent;
layout(location = 2) in vec3 worldLatitudeTangent;
layout(location = 3) in vec2 textureCoordinate;
layout(location = 4) flat in vec4 sun;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D lunarAlbedo;
layout(set = 0, binding = 1) uniform sampler2D lunarElevation;

void main() {
    // Per-pixel central differences over one elevation texel.
    vec2 texel = 1.0 / vec2(textureSize(lunarElevation, 0));
    float east = texture(lunarElevation, textureCoordinate + vec2(texel.x, 0.0)).r;
    float west = texture(lunarElevation, textureCoordinate - vec2(texel.x, 0.0)).r;
    float north = texture(lunarElevation, textureCoordinate + vec2(0.0, texel.y)).r;
    float south = texture(lunarElevation, textureCoordinate - vec2(0.0, texel.y)).r;
    // Differences are per texel, so scale them by resolution to get a true slope. The
    // constants keep RELIEF calibrated to the original 1024x512 map.
    vec2 slopeScale = 2.0 * sun.w * vec2(textureSize(lunarElevation, 0)) / vec2(1024.0, 512.0);
    vec3 normal = normalize(normalize(worldNormal)
        - normalize(worldLongitudeTangent) * (east - west) * slopeScale.x
        - normalize(worldLatitudeTangent) * (north - south) * slopeScale.y);

    float diffuse = max(dot(normal, normalize(sun.xyz)), 0.0);
    vec3 albedo = texture(lunarAlbedo, textureCoordinate).rgb;
    outColor = vec4(albedo * (0.08 + 0.92 * diffuse), 1.0);
}
