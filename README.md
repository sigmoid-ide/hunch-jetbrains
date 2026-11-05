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

<p align="center">
  <img src="media/marketplace/01-runs.png" width="100%" alt="The Hunch tool window in PyCharm: milestones, the tree of runs with their metric curves and conclusions, and one run's details" />
</p>

- **The tree of runs**, with each run's hypothesis, main metric and its curve, and your conclusion. It updates while runs train.
- **Run with Hunch**: one click next to the Run widget runs any Python configuration under Hunch, asking why first.
- **Keep or drop** a run, write its conclusion, or fix its hypothesis, right from the table.
- **Compare** two runs, or a run with the one it came from (Ctrl+D): config, metrics and every changed code file.
- **Milestones**: save one from the toolbar when you find something worth remembering, then browse your runs milestone by milestone.

## Install

Hunch is in review on JetBrains Marketplace. Once it is listed, install it from **Settings | Plugins | Marketplace**
by searching for "Hunch".

Until then, take `hunch-jetbrains-0.1.1.zip` from the latest
[CI run](https://github.com/sigmoid-ide/hunch-jetbrains/actions/workflows/ci.yml?query=branch%3Amain) (needs a GitHub
login) or build it with `./gradlew buildPlugin`, then in PyCharm open
**Settings | Plugins | ⚙ | Install Plugin from Disk...** and pick the zip.

The plugin reads the `.hunch` folder Hunch writes, so record runs with Hunch as usual: `pip install sigmoid-hunch`
in your project's interpreter. Saving milestones needs Hunch 0.3 or newer.

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
