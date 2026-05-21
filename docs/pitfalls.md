# Nion 开发踩坑记录

> 开发过程中遇到的所有坑和正确解法，持续更新。

---

## 001. UniFFI proc-macro 模式缺少 setup_scaffolding

**日期**：2026-05-20
**现象**：使用 `#[uniffi::export]`、`#[uniffi::Record]`、`#[uniffi::Object]` 等 derive 宏时，编译报错 `cannot find type UniFfiTag in the crate root`。

**错误写法**：
```rust
// 没有调用任何初始化宏
// 或者调用不存在的宏
uniffi::setup_product_branding!(); // ❌ 不存在这个宏
```

**正确写法**：
```rust
// 在 lib.rs 最顶部调用
uniffi::setup_scaffolding!();
```

**Cargo.toml**：
```toml
[dependencies]
uniffi = "0.31"  # 不需要特殊 feature

[lib]
name = "nion_core"
crate-type = ["cdylib", "lib"]  # 需要 cdylib 给 Android/iOS 用

# 不需要 build-dependencies
# 不需要 build.rs
# 不需要 .udl 文件
```

**关键点**：
- proc-macro 模式**不需要** UDL 文件、build.rs、`uniffi` 的 build feature
- `setup_scaffolding!()` 宏会生成 `UniFfiTag` 类型和必要的 FFI 胶水代码
- **不要**同时使用 `uniffi::setup_scaffolding!()` 和 `uniffi::include_scaffolding!("xxx.udl")`

---

## 002. UniFFI UDL 模式 weedle2 解析器报错

**日期**：2026-05-20
**现象**：使用 UDL 文件时，`uniffi::generate_scaffolding("src/nion.udl")` 报 `parse error`，内容看起来完全合法。

**原因**：未完全确认，可能是 weedle2 解析器对某些合法语法的兼容问题。在确认可复现的最小用例前，暂时切换到 proc-macro 模式规避。

**决策**：Nion 项目统一使用 proc-macro 模式，不使用 UDL。

---

## 模板

```
## XXX. 标题

**日期**：YYYY-MM-DD
**现象**：具体报错/行为

**错误写法**：
（代码）

**正确写法**：
（代码）

**关键点**：
- 要点1
- 要点2
```
