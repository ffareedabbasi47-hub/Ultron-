# Test report
Run locally (JVM harness with real Kotlin 2.0.21 compiler, stub JUnit): DbKeyPolicyTest 6, LaunchPolicyTest 5, ConsentPolicyTest 6, ResetEverythingTest 3 = 20 passed, 0 failed. Earlier harness: 91 passed + OpenMeteo 8.
Not run locally: Gradle/Android build, instrumented or emulator tests (BLOCKED: no Android SDK/emulator). Gradle unit tests + assembleDebug run only in GitHub Actions; result for this change set is reported separately after push.
