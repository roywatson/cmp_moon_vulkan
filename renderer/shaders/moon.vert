#version 450

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inTextureCoordinate;

layout(push_constant) uniform MoonView {
    mat4 modelViewProjection;
    mat4 model;
    vec4 sunDirection;  // xyz: light direction, w: relief strength.
} view;

layout(set = 0, binding = 1) uniform sampler2D lunarElevation;

layout(location = 0) out vec3 worldNormal;
layout(location = 1) out vec3 worldLongitudeTangent;
layout(location = 2) out vec3 worldLatitudeTangent;
layout(location = 3) out vec2 textureCoordinate;
layout(location = 4) flat out vec4 sun;

void main() {
    // LRO elevation is normalized. Keep displacement small relative to lunar radius:
    // it should add relief and a subtly irregular limb, not exaggerate terrain.
    float elevation = textureLod(lunarElevation, inTextureCoordinate, 0.0).r;
    vec3 displacedPosition = inPosition * (1.0 + (elevation - 0.5) * 0.012 * view.sunDirection.w);
    gl_Position = view.modelViewProjection * view.model * vec4(displacedPosition, 1.0);

    // Only the tangent frame is interpolated; the relief normal itself is rebuilt per
    // pixel in the fragment shader so crater rims stay sharp instead of smoothed across
    // whole triangles.
    vec3 referenceAxis = abs(inNormal.y) > 0.99 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    vec3 longitudeTangent = normalize(cross(referenceAxis, inNormal));
    vec3 latitudeTangent = normalize(cross(inNormal, longitudeTangent));
    mat3 model = mat3(view.model);
    worldNormal = model * inNormal;
    worldLongitudeTangent = model * longitudeTangent;
    worldLatitudeTangent = model * latitudeTangent;
    textureCoordinate = inTextureCoordinate;
    sun = view.sunDirection;
}
