// economy 模块：paper-api 与 lbr-api 已由根脚本以 compileOnly 方式加入。
// Vault API 仅编译期依赖；运行时只在服务器装有 Vault 时才会加载桥接类（com.lunarboreal.economy.vault.*）。
dependencies {
    compileOnly(rootProject.extra["vaultApi"] as String) { isTransitive = false }
}
