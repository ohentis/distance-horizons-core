#version 330 core

layout (location = 1) in vec4 aColor;
layout (location = 2) in mat4 aTransform;

uniform vec3 uOriginOffset;

in vec3 vPosition;

out vec4 fColor;

void main()
{
    gl_Position = aTransform * vec4(vPosition + uOriginOffset, 1.0);
    fColor = aColor;
}