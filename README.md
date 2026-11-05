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

1. Install Hunch in your project's interpreter: `pip install sigmoid-hunch`
2. Download the plugin zip from the [latest release](https://github.com/sigmoid-ide/hunch-jetbrains/releases/latest).
3. In PyCharm, open **Settings | Plugins | ⚙ | Install Plugin from Disk...** and pick the zip.

Hunch is in review on JetBrains Marketplace. Once it is listed, you can install it from **Settings | Plugins** instead.

<p align="center">Apache-2.0 · Part of Sigmoid IDE · Like it? Give it a star.</p>
