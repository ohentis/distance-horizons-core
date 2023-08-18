#version 150 core

in vec2 TexCoord;
in vec2 ViewRay;

out vec4 fragColor;

uniform sampler2D gSSAOMap;
uniform sampler2D gDepthMap;

void main()
{
    float fragmentDepth = texture(gDepthMap, TexCoord).r;
    // a fragment depth of "1" means the fragment wasn't drawn to,
    // we only want to apply SSAO to LODs, not to the sky outside the LODs
    if (fragmentDepth < 1)
    {
        fragColor = vec4(0.0, 0.0, 0.0, 1-texture(gSSAOMap, TexCoord).r);
    }
    else
    {
        // every pixel needs to be set to something, otherwise the pixel may be undefined by some drivers (specifically Intel)
        fragColor = vec4(0.0, 0.0, 0.0, 0.0);
    }
}
