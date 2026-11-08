package com.roywatson.lunar

// macOS reaches Vulkan through MoltenVK; Windows and Linux use the system Vulkan driver.
internal actual val rendererBackend: String =
    if (System.getProperty("os.name").startsWith("Mac")) "MoltenVK → Metal" else "Vulkan"
