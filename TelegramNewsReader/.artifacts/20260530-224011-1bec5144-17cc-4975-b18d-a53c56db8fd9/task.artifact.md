# Task Management

- [x] Fix loadChannels infinite wait
- [x] Fix TDLib encryption key loss
- [x] Fix silent news truncation
- [x] Fix hardcoded Edge TTS version
- [x] Fix privacy leaks in logs
- [x] Fix unclosed OkHttpClient singletons
	- [x] Research and Planning
		- [x] Analyze `AiProcessor.kt`, `EdgeTtsProvider.kt` and `TTSManager.kt`
	- [x] Implementation
		- [x] Create `HttpClients.kt` for centralized management
		- [x] Update `EdgeTtsProvider.kt` to use shared client
		- [x] Update `AiProcessor.kt` to use shared client
		- [x] Update `EdgeConfig.kt` to use shared client
		- [x] Update `TTSManager.kt` to shut down clients on clearInstance
	- [x] Verification
		- [x] Run `gradle_build` to check for compilation errors
		- [x] Verify logic via code review
