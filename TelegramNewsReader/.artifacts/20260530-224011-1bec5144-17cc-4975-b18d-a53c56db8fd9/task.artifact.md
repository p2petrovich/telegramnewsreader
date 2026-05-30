# Task Management

- [x] Fix loadChannels infinite wait
- [x] Fix TDLib encryption key loss
- [x] Fix silent news truncation
- [x] Fix hardcoded Edge TTS version
- [x] Fix privacy leaks in logs
- [x] Fix unclosed OkHttpClient singletons
- [x] Optimize getAllChannelsNewsCount performance
	- [x] Research and Planning
		- [x] Analyze `NewsService.kt` and `TelegramClient.kt`
	- [x] Implementation
		- [x] Update `NewsService.kt` with parallel counting and timeouts
	- [x] Verification
		- [x] Run `gradle_build` to check for compilation errors
		- [x] Verify logic via code review
