#version 150 core


//uniform vec3 modelOffset;
uniform float worldYOffset;

in vec2 vPos;
in uvec4 vPosition;
out vec3 vertexWorldPos;
out float vertexYPos;


void main()
{
//    vertexWorldPos = vPosition.xyz + modelOffset;
    vertexYPos = vPosition.y + worldYOffset;

    gl_Position = vec4(vPos, 1.0, 1.0);
}