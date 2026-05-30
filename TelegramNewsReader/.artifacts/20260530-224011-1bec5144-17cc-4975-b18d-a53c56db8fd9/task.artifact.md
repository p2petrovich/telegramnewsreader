# Task Management

- [x] Fix loadChannels infinite wait
- [x] Fix TDLib encryption key loss
- [x] Fix silent news truncation
- [x] Fix hardcoded Edge TTS version
- [x] Fix privacy leaks in logs
- [x] Fix unclosed OkHttpClient singletons
- [x] Optimize getAllChannelsNewsCount performance
- [x] Fix fragile WAV parsing and concatenation
	- [x] Research and Planning
		- [x] Analyze `TTSManager.kt` and `AudioUtils.kt`
	- [x] Implementation
		- [x] Update `TTSManager.readWavMeta()` to parse chunks correctly
		- [x] Update `AudioUtils.concatWavFiles()` to re-encode for stability
	- [x] Verification
		- [x] Run `gradle_build` to check for compilation errors
		- [x] Verify logic via code review
