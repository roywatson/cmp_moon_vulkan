#include "moon_log.h"

#include <cstdarg>
#include <cstdio>

#if defined(__ANDROID__)
#include <android/log.h>
#elif defined(__APPLE__)
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
#include <os/log.h>
#endif
#endif

namespace moon {
namespace {

enum class Level { kInfo, kError };

void Write(Level level, const char* format, va_list arguments) {
#if defined(__ANDROID__)
  __android_log_vprint(level == Level::kError ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "moon", format, arguments);
#elif defined(__APPLE__) && TARGET_OS_IPHONE
  char message[512];
  std::vsnprintf(message, sizeof(message), format, arguments);
  os_log_with_type(OS_LOG_DEFAULT, level == Level::kError ? OS_LOG_TYPE_ERROR : OS_LOG_TYPE_INFO, "[moon] %{public}s",
                   message);
#else
  (void)level;
  std::fputs("[moon] ", stderr);
  std::vfprintf(stderr, format, arguments);
  std::fputc('\n', stderr);
#endif
}

}  // namespace

void LogInfo(const char* format, ...) {
  va_list arguments;
  va_start(arguments, format);
  Write(Level::kInfo, format, arguments);
  va_end(arguments);
}

void LogError(const char* format, ...) {
  va_list arguments;
  va_start(arguments, format);
  Write(Level::kError, format, arguments);
  va_end(arguments);
}

}  // namespace moon
