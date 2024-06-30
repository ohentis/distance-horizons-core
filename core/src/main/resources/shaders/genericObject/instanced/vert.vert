#version 330 core

layout (location = 1) in vec4 aColor;
layout (location = 2) in vec3 aTranslate;
layout (location = 3) in vec3 aScale;

uniform vec3 uOffset;
uniform vec3 uCameraPos;
uniform mat4 uProjectionMvm;
uniform int uSkyLight;
uniform int uBlockLight;
uniform sampler2D uLightMap;

in vec3 vPosition;

out vec4 fColor;

void main()
{
    // aTranslate - moves the vertex to the boxGroup's relative position
    // uOffset - moves the vertex to the boxGroup's position
    // uCameraPos - moves the vertex into camera space
    float transX = aTranslate.x + uOffset.x - uCameraPos.x;
    float transY = aTranslate.y + uOffset.y - uCameraPos.y;
    float transZ = aTranslate.z + uOffset.z - uCameraPos.z;
    
    float scaleX = aScale.x;
    float scaleY = aScale.y;
    float scaleZ = aScale.z;
    
    // combination translation and scaling matrix
    mat4 transform = mat4(
        scaleX, 0.0,    0.0,    0.0,
        0.0,    scaleY, 0.0,    0.0,
        0.0,    0.0,    scaleZ, 0.0,
        transX, transY, transZ, 1.0
    );
    
    gl_Position = uProjectionMvm * transform * vec4(vPosition, 1.0);

    float blockLight = (float(uBlockLight)+0.5) / 16.0;
    float skyLight = (float(uSkyLight)+0.5) / 16.0;
    vec4 lightColor = vec4(texture(uLightMap, vec2(blockLight, skyLight)).xyz, 1.0);
    
    fColor = lightColor * aColor;
}