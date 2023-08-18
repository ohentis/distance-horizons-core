package com.seibel.distanthorizons.api.enums.rendering;

/**
 * LINEAR,				<br>
 * EXPONENTIAL, 		<br>
 * EXPONENTIAL_SQUARED 	<br>
 *
 * @author Leetom
 * @version 2022-6-30
 * @since API 1.0.0
 */
public enum EFogFalloff
{
	// Reminder:
	// when adding items up the API minor version
	// when removing items up the API major version
	
	
	LINEAR,
	EXPONENTIAL,
	EXPONENTIAL_SQUARED,
	// TEXTURE_BASED, // TODO: Impl this
}
