# Task Management

- [x] Fix loadChannels infinite wait
- [x] Fix TDLib encryption key loss
- [x] Fix silent news truncation
	- [x] Research and Planning
		- [x] Analyze `TextProcessor.kt` and `NewsService.kt`
		- [x] Analyze `ProgressCallback` interface
	- [x] Implementation
		- [x] Update `TextProcessor.kt` with explicit and news-only truncation
		- [x] Update `ProgressCallback` in `NewsService.kt`
		- [x] Update `NewsService.kt` to handle truncation signal
		- [x] Update `MainActivity.kt` to show truncation notice
	- [x] Verification
		- [x] Run `gradle_build` to check for compilation errors
		- [x] Verify logic via code review
