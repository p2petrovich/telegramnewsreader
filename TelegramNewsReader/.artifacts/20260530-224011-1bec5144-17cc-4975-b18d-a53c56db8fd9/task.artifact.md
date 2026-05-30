# Task Management

- [x] Fix loadChannels infinite wait
- [x] Fix TDLib encryption key loss
- [x] Fix silent news truncation
- [x] Fix hardcoded Edge TTS version
- [x] Fix privacy leaks in logs
	- [x] Research and Planning
		- [x] Analyze `TextProcessor.kt`, `Deduplicator.kt` and `proguard-rules.pro`
	- [x] Implementation
		- [x] Create `Logx.kt` for gated logging
		- [x] Update `proguard-rules.pro` with stricter log removal rules
		- [x] Replace sensitive `Log.d` calls in `TextProcessor.kt` with metrics
		- [x] Replace sensitive `android.util.Log.d` calls in `Deduplicator.kt` with metrics
	- [x] Verification
		- [x] Run `gradle_build` to check for compilation errors
		- [x] Verify logic via code review
