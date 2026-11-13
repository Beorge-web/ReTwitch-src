# Vendored helpers (`src/vendor/`)

These Kotlin sources are copied verbatim from Morphe's patch library and
compiled into `retwitch-patches.jar` alongside our own patches. They are
kept here (rather than pulled as a Gradle/Maven dependency) because the
kitchen builds patches with a bare `kotlinc -cp morphe-cli-all.jar`
invocation, not a Gradle project, so there is no dependency resolver to
fetch `app.morphe:morphe-patches-library` for us.

## Source & license

- Upstream: https://github.com/MorpheApp/morphe-patches-library
  (`patch-library/src/main/kotlin/app/morphe/...`)
- License: **GPL-3.0-or-later**, with Morphe's additional §7 terms
  (attribution + no removal of author designations / "Morphe" naming
  restrictions). The upstream copyright headers are left intact at the top
  of each file. Do not strip them.

Because ReTwitch links against and redistributes this GPL-3.0 code, the
patch bundle as a whole is GPL-3.0. Keep this directory's provenance
visible.

## Files

| File | What it gives us |
|------|------------------|
| `util/BytecodeUtils.kt` | `returnEarly()/returnLate()`, `indexOfFirst*Instruction[Reversed][OrThrow]`, `indexOfFirst(Literal/ResourceId/String)Instruction*`, `insertLiteralOverride`, `injectHideViewCall`, `addInstructionsAtControlFlowLabel`, `cloneMutable`/`cloneParameters`, `findInstructionIndicesReversed*`, `traverseClassHierarchy`, register/parameter helpers. |
| `util/ResourceUtils.kt` | `ResourcePatchContext.copyResources(...)`, `ResourceGroup`, `String.copyXmlNode(...)`, `NodeList.findElementByAttributeValue[OrThrow]`, `Node.adoptChild/insertFirst/removeFromParent`, `Document.getNode`, XML DOM helpers for `resourcePatch {}`. |
| `util/FreeRegisterProvider.kt` | `Method.findFreeRegister(...)` and `getFreeRegisterProvider(...)` - real dataflow-aware free-register search (needed for injecting into register-tight methods without clobbering live values). |
| `resources/ResourceMappingPatch.kt` | `resourceMappingPatch` + `getResourceId(type, name)` / `hasResourceId(...)` and the `resourceLiteral(...)` instruction filter - lets fingerprints anchor on decoded resource-id integer literals by *name*. Depends on running `resourceMappingPatch` first (add it via `dependsOn`). |

Only these four were vendored (the subset our patches actually need or are
likely to reach for next). If you need more from the library
(`ReplaceStringPatch`, `HexPatchBuilder`, `TransformInstructionsPatch`,
etc.), copy the file here the same way and keep its header.
