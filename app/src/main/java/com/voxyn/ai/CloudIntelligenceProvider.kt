package com.voxyn.ai

/**
 * CloudIntelligenceProvider — optional cloud reasoning used as the last step of
 * the hybrid route (local → web → cloud). Credentials come from build config,
 * never from source code; the provider is absent when no key is configured.
 */
interface CloudIntelligenceProvider : AiProvider
