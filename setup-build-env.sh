#!/bin/bash
# ═══════════════════════════════════════════════════════════
# 猫猫互联助手 - 构建环境一键安装脚本
# 用法: bash setup-build-env.sh
# ═══════════════════════════════════════════════════════════
set -e

TOOLS_DIR="/data/user/work/tools"
JDK_DIR="/data/user/work/jdk17"
SDK_DIR="/data/user/work/android-sdk"
GRADLE_VERSION="7.6.3"
JDK_VERSION="17"

echo "========================================"
echo "  猫猫互联助手 - 构建环境安装"
echo "========================================"

# ── 1. JDK 17 ──────────────────────────────────────────────
if [ -f "$JDK_DIR/bin/java" ]; then
    echo "[✓] JDK 17 已存在: $JDK_DIR"
else
    echo "[↓] 安装 JDK 17 (Temurin)..."
    mkdir -p "$TOOLS_DIR"
    curl -sL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" -o "$TOOLS_DIR/jdk17.tar.gz"
    mkdir -p "$JDK_DIR"
    tar -xzf "$TOOLS_DIR/jdk17.tar.gz" -C "$JDK_DIR" --strip-components=1
    rm -f "$TOOLS_DIR/jdk17.tar.gz"
    echo "[✓] JDK 17 安装完成"
fi

# ── 2. Gradle 7.6.3 ────────────────────────────────────────
if [ -f "$TOOLS_DIR/gradle-$GRADLE_VERSION/bin/gradle" ]; then
    echo "[✓] Gradle $GRADLE_VERSION 已存在: $TOOLS_DIR/gradle-$GRADLE_VERSION"
else
    echo "[↓] 安装 Gradle $GRADLE_VERSION..."
    mkdir -p "$TOOLS_DIR"
    curl -sL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$TOOLS_DIR/gradle.zip"
    cd "$TOOLS_DIR"
    unzip -qo gradle.zip
    rm -f gradle.zip
    echo "[✓] Gradle $GRADLE_VERSION 安装完成"
fi

# ── 3. Android SDK (platform 33 + build-tools) ─────────────
if [ -f "$SDK_DIR/platforms/android-33/android.jar" ]; then
    echo "[✓] Android SDK 已存在: $SDK_DIR"
else
    echo "[↓] 安装 Android SDK..."
    mkdir -p "$SDK_DIR/cmdline-tools"
    curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip" -o "$TOOLS_DIR/cmdline-tools.zip"
    cd "$SDK_DIR/cmdline-tools"
    unzip -qo "$TOOLS_DIR/cmdline-tools.zip"
    mv cmdline-tools latest 2>/dev/null || true
    rm -f "$TOOLS_DIR/cmdline-tools.zip"

    # 接受许可
    export JAVA_HOME="$JDK_DIR"
    yes | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --licenses --sdk_root="$SDK_DIR" > /dev/null 2>&1

    # 安装平台和构建工具
    "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --install \
        "platforms;android-33" \
        "build-tools;33.0.2" \
        "platform-tools" \
        --sdk_root="$SDK_DIR" 2>&1 | tail -3
    echo "[✓] Android SDK 安装完成"
fi

# ── 4. 输出环境变量 ─────────────────────────────────────────
echo ""
echo "========================================"
echo "  构建环境就绪！"
echo "========================================"
echo ""
echo "请使用以下环境变量编译："
echo ""
echo "  export JAVA_HOME=$JDK_DIR"
echo "  export ANDROID_HOME=$SDK_DIR"
echo "  $TOOLS_DIR/gradle-$GRADLE_VERSION/bin/gradle assembleDebug --no-daemon"
echo ""

# ── 5. 验证安装 ─────────────────────────────────────────────
echo "── 验证 ──"
$JDK_DIR/bin/java -version 2>&1 | head -1
echo "Gradle: $($TOOLS_DIR/gradle-$GRADLE_VERSION/bin/gradle --version 2>/dev/null | grep 'Gradle ' | head -1)"
ls "$SDK_DIR/platforms/android-33/android.jar" > /dev/null 2>&1 && echo "Android SDK: OK" || echo "Android SDK: MISSING"
echo ""
echo "Done!"
