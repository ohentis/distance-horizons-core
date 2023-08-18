#version 150 core

in uvec4 vPosition;
out vec4 vPos;
in vec4 color;

out vec4 vertexColor;
out vec3 vertexWorldPos;
out float vertexYPos;

uniform mat4 combinedMatrix;
uniform vec3 modelOffset;
uniform float worldYOffset;

uniform int worldSkyLight;
uniform sampler2D lightMap;
uniform float mircoOffset;

uniform float earthRadius;

/** 
 * Vertex Shader
 * 
 * author: James Seibel
 * author: TomTheFurry
 * author: stduhpf
 * updated: coolGi
 * version: 24-1-2023
 */
void main()
{
    vPos = vPosition; // This is so it can be passed to the fragment shader

    vertexWorldPos = vPosition.xyz + modelOffset;

    vertexYPos = vPosition.y + worldYOffset;

    uint meta = vPosition.a;

    uint mirco = (meta & 0xFF00u) >> 8u; // mirco offset which is a xyz 2bit value
    // 0b00 = no offset
    // 0b01 = positive offset
    // 0b11 = negative offset
    // format is: 0b00zzyyxx
    float mx = (mirco & 1u) != 0u ? mircoOffset : 0.0;
    mx = (mirco & 2u) != 0u ? -mx : mx;
    float my = (mirco & 4u) != 0u ? mircoOffset : 0.0;
    my = (mirco & 8u) != 0u ? -my : my;
    float mz = (mirco & 16u) != 0u ? mircoOffset : 0.0;
    mz = (mirco & 32u) != 0u ? -mz : mz;
    vertexWorldPos.x += mx;
    vertexWorldPos.y += my;
    vertexWorldPos.z += mz;

    #if 0
        // Old (disabled) vertex transformation logic - Leetom

        // Calculate the vertex pos due to curvature of the earth
        // We use spherical coordinates to calculate the vertex position
        //if (vertexWorldPos.x == 0.0 && vertexWorldPos.z == 0.0)
        //{
        //    // In the center. No curvature needed
        //}
        //else
        //{
            float theta = atan(vertexWorldPos.z, vertexWorldPos.x); // in radians (-pi, pi)
            float trueY = earthRadius + vertexWorldPos.y; // true Y position, or height
            float phi = sqrt(vertexWorldPos.z * vertexWorldPos.z + vertexWorldPos.x * vertexWorldPos.x) / trueY;
            // Convert spherical coordinates to cartesian coordinates
            vertexWorldPos.x = trueY * sin(phi) * cos(theta);
            vertexWorldPos.z = trueY * sin(phi) * sin(theta);
            vertexWorldPos.y = trueY * cos(phi) - earthRadius;
        //}

    #else
        // new vertex transformation logic - stduhpf

        float localRadius = earthRadius + vertexYPos;// vertexWorldPos.y + cameraPosition.y - Center_Y;

        float phi = length(vertexWorldPos.xz) / localRadius;

        vertexWorldPos.y += (cos(phi) - 1.0) * localRadius;
        vertexWorldPos.xz = vertexWorldPos.xz * sin(phi) / phi;
    #endif

    uint lights = meta & 0xFFu;

    float light2 = (mod(float(lights), 16.0) + 0.5) / 16.0;
    float light = (float(lights / 16u) + 0.5) / 16.0;
    vertexColor = color * vec4(texture(lightMap, vec2(light, light2)).xyz, 1.0);

    gl_Position = combinedMatrix * vec4(vertexWorldPos, 1.0);
}
