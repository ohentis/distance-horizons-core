
in vec2 TexCoord;

out vec4 fragColor;

uniform sampler2D gDepthMap;
// model view matrix and projection matrix
uniform mat4 gMvmProj;

uniform float fogScale;
uniform float fogVerticalScale;
uniform float nearFogStart;
uniform float nearFogLength;
uniform int fullFogMode;

uniform vec4 fogColor;


/* ========MARCO DEFINED BY RUNTIME CODE GEN=========

float farFogStart;
float farFogLength;
float farFogMin;
float farFogRange;
float farFogDensity;

float heightFogStart;
float heightFogLength;
float heightFogMin;
float heightFogRange;
float heightFogDensity;
*/

// method definitions
// ==== The below 5 methods will be run-time generated. ====
float getNearFogThickness(float dist);
float getFarFogThickness(float dist);
float getHeightFogThickness(float dist);
float calculateFarFogDepth(float horizontal, float dist, float nearFogStart);
float calculateHeightFogDepth(float vertical, float realY);
float mixFogThickness(float near, float far, float height);
// =========================================================


// Puts steps in a float
// EG. setting stepSize to 4 then this would be the result of this function
// In:  0.0, 0.1, 0.2, 0.3,  0.4,  0.5, 0.6, ..., 1.1, 1.2, 1.3
// Out: 0.0, 0.0, 0.0, 0.25, 0.25, 0.5, 0.5, ..., 1.0, 1.0, 1.25
float quantize(float val, int stepSize) {
    return floor(val*stepSize)/stepSize;
}

// The modulus function dosnt exist in GLSL so I made my own
// To speed up the mod function, this only accepts full numbers for y
float mod(float x, int y) {
    return x - y * floor(x/y);
}


vec3 calcViewPosition(float fragmentDepth)
{
    vec4 ndc = vec4(TexCoord.xy, fragmentDepth, 1.0);
    ndc.xyz = ndc.xyz * 2.0 - 1.0;

    // TODO: This inverse() should be moved CPU side
    vec4 eyeCoord = inverse(gMvmProj) * ndc;
    return eyeCoord.xyz / eyeCoord.w;
}

/**
 * Fragment shader for fog.
 * This should be passed last so it applies above other affects like AO
 *
 * version: 2023-6-21
 */
void main() 
{
    float vertexYPos = 100.0f;
    float fragmentDepth = texture(gDepthMap, TexCoord).r;

    // a fragment depth of "1" means the fragment wasn't drawn to,
    // we only want to apply Fog to LODs, not to the sky outside the LODs
    if (fragmentDepth < 1)
    {
        if (fullFogMode == 0)
        {
            // render fog based on distance from the camera
            vec3 vertexWorldPos = calcViewPosition(fragmentDepth);

            float horizontalDist = length(vertexWorldPos.xz) * fogScale;
            float heightDist = calculateHeightFogDepth(vertexWorldPos.y, vertexYPos) * fogVerticalScale;
            float farDist = calculateFarFogDepth(horizontalDist, length(vertexWorldPos.xyz) * fogScale, nearFogStart);

            float nearFogThickness = getNearFogThickness(horizontalDist);
            float farFogThickness = getFarFogThickness(farDist);
            float heightFogThickness = getHeightFogThickness(heightDist);
            float mixedFogThickness = mixFogThickness(nearFogThickness, farFogThickness, heightFogThickness);
            mixedFogThickness = clamp(mixedFogThickness, 0.0, 1.0);

            fragColor = vec4(fogColor.rgb, mixedFogThickness);
        }
        else if (fullFogMode == 1)
        {
            // render everything with the fog color
            fragColor = vec4(fogColor.rgb, 1.0);
        }
        else
        {
            // test code.

            // this can be fired by manually changing the fullFogMode to a (normally)
            // invalid value (like 7). By having a separate if statement defined by
            // a uniform we don't have to worry about GLSL optimizing away different
            // options when testing, causing a bunch of headaches if we just want to render the screen red.

            float depthValue = texture(gDepthMap, TexCoord).r;
            fragColor = vec4(vec3(depthValue), 1.0); // Convert depth value to grayscale color
        }
    }
    else
    {
        // every pixel needs to be set to something, otherwise the pixel may be undefined by some drivers (specifically Intel)
        fragColor = vec4(0.0, 0.0, 0.0, 0.0);
    }
}



// Are these still needed?
float linearFog(float x, float fogStart, float fogLength, float fogMin, float fogRange) {
    x = clamp((x-fogStart)/fogLength, 0.0, 1.0);
    return fogMin + fogRange * x;
}

float exponentialFog(float x, float fogStart, float fogLength,
float fogMin, float fogRange, float fogDensity) {
    x = max((x-fogStart)/fogLength, 0.0) * fogDensity;
    return fogMin + fogRange - fogRange/exp(x);
}

float exponentialSquaredFog(float x, float fogStart, float fogLength,
float fogMin, float fogRange, float fogDensity) {
    x = max((x-fogStart)/fogLength, 0.0) * fogDensity;
    return fogMin + fogRange - fogRange/exp(x*x);
}