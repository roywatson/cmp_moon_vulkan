#pragma once

// Diagnostics shared by the renderer core and the platform bridges. Messages go where each
// platform's developers look: logcat on Android, the unified log on iOS, stderr elsewhere.

#if defined(__GNUC__) || defined(__clang__)
#define MOON_PRINTF_FORMAT(format_index) __attribute__((format(printf, format_index, format_index + 1)))
#else
#define MOON_PRINTF_FORMAT(format_index)
#endif

namespace moon {

void LogInfo(const char* format, ...) MOON_PRINTF_FORMAT(1);
void LogError(const char* format, ...) MOON_PRINTF_FORMAT(1);

}  // namespace moon
