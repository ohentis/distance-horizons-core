#version 150 core

in vec2 TexCoord;

out vec4 fragColor;

uniform sampler2D gDepthMap;
uniform float gSampleRad;
uniform float gFactor;
uniform float gPower;
uniform mat4 gProj;

const int MAX_KERNEL_SIZE = 32;
const float INV_MAX_KERNEL_SIZE_F = 1.0 / float(MAX_KERNEL_SIZE);
const vec2 HALF_2 = vec2(0.5);
uniform vec3 gKernel[MAX_KERNEL_SIZE];

vec3 calcViewPosition(vec2 coords) {
    float fragmentDepth = texture(gDepthMap, coords).r;

    vec4 ndc = vec4(
        coords.x * 2.0 - 1.0,
        coords.y * 2.0 - 1.0,
        fragmentDepth * 2.0 - 1.0,
        1.0
    );

    vec4 vs_pos = inverse(gProj) * ndc;
    vs_pos.xyz = vs_pos.xyz / vs_pos.w;
    return vs_pos.xyz;
}

void main()
{
    vec3 viewPos = calcViewPosition(TexCoord);
    vec3 viewNormal = normalize(cross(dFdy(viewPos.xyz), dFdx(viewPos.xyz)) * -1.0);

    vec3 randomVec = vec3(0.0, -1.0, 0.0);

    vec3 tangent = normalize(randomVec - viewNormal * dot(randomVec, viewNormal));
    vec3 bitangent = cross(viewNormal, tangent);
    mat3 TBN = mat3(tangent, bitangent, viewNormal);

    float occlusion_factor = 0.0;
    for (int i = 0; i < MAX_KERNEL_SIZE; i++) 
    {
        vec3 samplePos = vec3(0.0) + (TBN * gKernel[i]);
        samplePos = viewPos + samplePos * gSampleRad;

        vec4 offset = gProj * vec4(samplePos + viewPos, 1.0);
        offset.xy /= offset.w;
        offset.xy = offset.xy * HALF_2 + HALF_2;

        float geometryDepth = calcViewPosition(offset.xy).z;

        float rangeCheck = smoothstep(0.0, 1.0, gSampleRad / abs(viewPos.z - geometryDepth));
        occlusion_factor += float(geometryDepth >= samplePos.z + 0.05) * rangeCheck;

    }

    float visibility_factor = 1.0 - (occlusion_factor / MAX_KERNEL_SIZE);
    fragColor = vec4(clamp(1.0 - ((1.0 - pow(visibility_factor, gFactor)) * gPower), 0.1, 1.0));
}