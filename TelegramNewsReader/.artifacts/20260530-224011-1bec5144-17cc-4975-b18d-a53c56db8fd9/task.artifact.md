# Task Management

- [x] Fix loadChannels infinite wait
- [x] Fix TDLib encryption key loss
- [x] Fix silent news truncation
- [x] Fix hardcoded Edge TTS version
	- [x] Research and Planning
		- [x] Analyze `EdgeTtsProvider.kt` and `TTSManager.kt`
		- [x] Verify API endpoint for Chromium versions
	- [x] Implementation
		- [x] Create `EdgeConfig.kt` for dynamic version management
		- [x] Update `EdgeTtsProvider.kt` to use dynamic version and handle 403
		- [x] Update `TTSManager.kt` to pass context and refresh version
		- [x] Update `NewsService.kt` to trigger version refresh
	- [x] Verification
		- [x] Run `gradle_build` to check for compilation errors
		- [x] Verify logic via code review
