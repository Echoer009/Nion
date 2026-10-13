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

## 003. 播放页封面入场来源与收起目的地不能共用一条规则

**日期**：2026-10-12
**现象**：从曲目行打开播放页后，收起封面飞回列表行，而不是底部播放栏；切歌后还会依赖新曲目行是否在列表中。

**原因**：`openedFromTrackId` 同时决定入场与收起的配对 key，把打开来源误当成返回目标。

**正确做法**（`MusicScreen.kt`）：
- 大封面在整次展开/收起期间保持同一个共享身份，切歌只换内容；只有 Pager 当前页注册共享元素。
- 点歌入场时由来源行配对，播放栏不参与封面配对；收起时来源行释放共享身份、原位淡入，由播放栏接手大封面的 key。
- 等播放页退场组合销毁后再清空来源，避免中途换 key，也保证下次点歌前行封面已注册好。
- 封面 overlay 层级固定在背景之上、控件之下，不能依赖配对方注册顺序，否则收起时封面会被退出背景遮住。

**验证**：模拟器录屏检查首次点歌、播放栏展开/收起、入场末段打断后收起、切到被列表过滤掉的下一首后收起；慢放和常速均观察到封面飞入播放栏。系统返回处理不在此次修复范围。

---

## 004. 云端封面缓存：换歌封面不变、歌单封面永不更新、占位图被永久缓存

**日期**：2026-10-13
**现象**：
- 迷你播放条点"下一首"后，标题已是新歌，封面还是上一首。
- 歌单增删曲目后封面拼贴永远是第一次看到的样子；空歌单加了歌后一直显示服务器的灰色占位图。
- 服务器暂未解析出封面时回的占位图被当成正式封面落盘，之后补上封面也看不到。

**原因**：
1. `CoverArt` 用 `produceState(initialValue = 内存缓存, key1 = cacheKey)`。`produceState` 内部的 `remember` 不带 key，换 key 后旧位图原样保留；再叠加"`value == null` 才加载"的守卫，新封面根本不加载。凡是同一个 `CoverArt` 实例换了封面来源（迷你条切歌、歌单头部首曲变化、无 key 的 Lazy 行被复用）都中招。
2. 歌单封面由客户端自己拼 `getCoverArt?id=pl-<id>`。Navidrome（0.64）下发的令牌是 `pl-<id>_<内容哈希>`，图一变令牌就变；自己拼的 `pl-<id>` 永远不变，磁盘缓存又不过期，第一张图被永久钉住。歌单无图时服务端下发空令牌，客户端照样请求，拿到的是占位图。
3. Navidrome 的占位图响应头是 `Cache-Control: no-store`，且不按 `size` 缩放（固定 1024px）。旧实现无视响应头一律重编码成 JPEG 落盘，全尺寸解码一张占 4MB 内存。

**正确做法**（`MusicScreen.kt` 封面加载段 / `SubsonicClient.parsePlaylist`）：
- 位图状态用 `remember(cacheKey) { mutableStateOf(CoverArtCache.get(cacheKey)) }` + `LaunchedEffect(cacheKey)`，换 key 当帧重置。
- 封面一律使用服务端下发的 `coverArt` 令牌，空令牌 = 无封面、不请求。
- `no-store` 响应只进内存不落盘；解码按 URL 的 `size` 降采样。
- 磁盘缓存存原始字节、临时文件 + rename 原子写入、读命中刷新 lastModified（真 LRU）；缓存 key 带服务器段 `web:<host[:port]/path>|<令牌>|<size>`。
- 同 key 并发加载走在途表去重，只下载一次。

**关键点**：
- `produceState` 的 key 只重启 producer，不重置 state；需要"换 key 即换初值"时用 `remember(key)`。
- 对接 Navidrome 时不要自己拼任何 `xx-<id>` 封面 id，令牌里的哈希就是缓存失效机制。
- 缓存 key 含 size 时，同一封面的 150/300/600 互不命中：大封面首帧要拿已解码的其它尺寸当过渡图（`CoverArtCache.getAnyVariant`），否则打开专辑详情、点歌展开播放页时 morph 飞的是占位图。
- morph 的目标页不能整页换成转圈：专辑详情曲目加载中只把曲目区换成转圈，头部（大封面所在、morph 配对目标）照常渲染。

**验证**：模拟器 + 仿 Navidrome 0.64 头部语义的本地假服务器，新旧两版对照：旧版切歌后迷你条仍是上一首封面、服务端换歌单图后仍显示旧图、占位图被存成 JPEG；新版迷你条随切歌更新、歌单图随令牌更新、冷启动只重拉 2 张 `no-store` 占位图、其余全部磁盘命中、无痕冷缓存每个 key 只请求一次。

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
