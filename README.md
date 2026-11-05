<div align="center">
  <br/>
  <img src="https://raw.githubusercontent.com/sigmoid-ide/hunch/main/media/logo.svg" width="140" alt="Hunch logo" />

# Hunch for PyCharm

**Every hunch, tracked. Now in your IDE.**

<a href="https://github.com/sigmoid-ide/hunch-jetbrains/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/sigmoid-ide/hunch-jetbrains/ci.yml?branch=main&style=for-the-badge&label=ci" alt="CI status" /></a>
<img src="https://img.shields.io/badge/PyCharm-2026.1%2B-6E56CF?style=for-the-badge&logo=pycharm&logoColor=white" alt="PyCharm 2026.1 or newer" />
<a href="https://github.com/sigmoid-ide/hunch-jetbrains/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-6E56CF?style=for-the-badge" alt="License: Apache-2.0" /></a>

</div>

Your [Hunch](https://github.com/sigmoid-ide/hunch) runs in a tool window: what each run tried, how it went,
and what it showed. Any two runs compare in the diff viewer.

- **The tree of runs**, with each run's hypothesis, main metric and its curve, and your conclusion. It updates while runs train.
- **Keep or drop** a run, write its conclusion, or fix its hypothesis, right from the table.
- **Compare** two runs, or a run with the one it came from (Ctrl+D): config, metrics and every changed code file.
- **Milestones**: save one from the toolbar when you find something worth remembering, then browse your runs milestone by milestone.

## Install

The plugin is not on JetBrains Marketplace yet. Build it, then in PyCharm open
**Settings | Plugins | ⚙ | Install Plugin from Disk...** and pick the zip:

```bash
./gradlew buildPlugin   # build/distributions/hunch-jetbrains-0.1.0.zip
```

Every [CI run](https://github.com/sigmoid-ide/hunch-jetbrains/actions/workflows/ci.yml) also keeps the zip as a download.

The plugin reads the `.hunch` folder Hunch writes, so record runs with Hunch as usual (`pip install sigmoid-hunch`).
Saving milestones runs `hunch milestone` with the project's interpreter; until the next Hunch release, that needs
Hunch from main: `pip install "git+https://github.com/sigmoid-ide/hunch"`.

## Develop

```bash
./gradlew test       # reads a store written by the real hunch CLI
./gradlew runIde     # a sandbox PyCharm with the plugin
```

To try it on a week of made-up runs, build the demo project (it lands in `hunch-demo`, next to this
repository), then open it in the sandbox:

```bash
python src/test/fixtures/make_demo.py <python with hunch installed>
./gradlew runIde --args="<path to hunch-demo>"
```

`src/test/fixtures/make_store.py` rebuilds the test store the same way.

<p align="center">Apache-2.0 · Part of Sigmoid IDE · Like it? Give it a star.</p>
