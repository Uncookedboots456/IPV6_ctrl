const MODULE_SCRIPT = "/data/adb/modules/ipv6_ctrl/scripts/ipv6_manager.sh";

const stateBadge = document.querySelector("#stateBadge");
const output = document.querySelector("#output");
const enableBtn = document.querySelector("#enableBtn");
const disableBtn = document.querySelector("#disableBtn");
const refreshBtn = document.querySelector("#refreshBtn");
const externalTestBtn = document.querySelector("#externalTestBtn");

let ksuExec = null;
let isRunning = false;

function setBusy(isBusy) {
  enableBtn.disabled = isBusy;
  disableBtn.disabled = isBusy;
  refreshBtn.disabled = isBusy;
  externalTestBtn.disabled = isBusy;
}

function writeTerminal(command, text) {
  output.textContent = `$ ${command}\n${text}`;
}

function appendTerminal(text) {
  output.textContent = `${output.textContent}\n${text}`;
}

function setBadge(state, label) {
  stateBadge.textContent = label;
  stateBadge.className = `badge ${state}`;
}

function renderStatus(data) {
  const state = data.state || "unknown";
  const label = state === "enabled" ? "Enabled" : state === "disabled" ? "Disabled" : "Unknown";
  const rows = Array.isArray(data.interfaces) ? data.interfaces.length : 0;

  setBadge(state, label);
  writeTerminal("ipv6_manager json-status", `IPv6: ${state}\nKernel entries: ${rows}`);
}

async function loadKernelSU() {
  try {
    const mod = await import("kernelsu");
    if (typeof mod.exec === "function") {
      ksuExec = mod.exec;
      setBadge("pending", "Ready");
      return true;
    }
  } catch (error) {
    ksuExec = null;
  }

  setBadge("error", "Preview");
  writeTerminal(
    "kernelsu",
    "KernelSU API is unavailable in this preview. Open this page inside KernelSU Manager."
  );
  return false;
}

async function runShell(command) {
  if (!ksuExec && !(await loadKernelSU())) {
    throw new Error("KernelSU API unavailable");
  }

  const result = await Promise.resolve(ksuExec(command));
  if (!result || typeof result !== "object") {
    throw new Error("KernelSU exec returned no result");
  }

  return result;
}

function formatExecResult(result) {
  const errno = result && typeof result.errno !== "undefined" ? result.errno : "unknown";
  const stdout = (result && result.stdout ? result.stdout : "").trim();
  const stderr = (result && result.stderr ? result.stderr : "").trim();
  const parts = [`errno: ${errno}`];

  if (stdout) {
    parts.push(`stdout:\n${stdout}`);
  }
  if (stderr) {
    parts.push(`stderr:\n${stderr}`);
  }
  return parts.join("\n");
}

function beginCommand(command) {
  if (isRunning) {
    appendTerminal("[info] Command already running; repeated tap ignored.");
    return false;
  }

  isRunning = true;
  setBusy(true);
  setBadge("running", "Running");
  writeTerminal(command, "Running...");
  return true;
}

function endCommand() {
  isRunning = false;
  setBusy(false);
}

async function refreshStatus() {
  if (!beginCommand("ipv6_manager json-status")) {
    return;
  }

  try {
    const result = await runShell(`sh ${MODULE_SCRIPT} json-status`);
    if (result.errno !== 0) {
      throw new Error(formatExecResult(result));
    }
    const stdout = (result.stdout || "").trim();
    let data;
    try {
      data = JSON.parse(stdout || "{}");
    } catch (parseError) {
      throw new Error(`Status JSON parse failed: ${parseError.message}\nraw:\n${stdout}`);
    }
    if (!data.ok) {
      throw new Error(data.error || "status failed");
    }
    renderStatus(data);
  } catch (error) {
    setBadge("error", "Error");
    writeTerminal("ipv6_manager json-status", String(error.message || error));
  } finally {
    endCommand();
  }
}

async function runAction(action) {
  if (!beginCommand(`ipv6_manager ${action}`)) {
    return;
  }

  try {
    const result = await runShell(`sh ${MODULE_SCRIPT} ${action}`);
    if (result.errno !== 0) {
      throw new Error(formatExecResult(result));
    }

    writeTerminal(`ipv6_manager ${action}`, formatExecResult(result));

    const statusResult = await runShell(`sh ${MODULE_SCRIPT} json-status`);
    if (statusResult.errno !== 0) {
      appendTerminal(`\n[warn] Status refresh failed after ${action}:\n${formatExecResult(statusResult)}`);
      return;
    }

    try {
      const data = JSON.parse((statusResult.stdout || "").trim() || "{}");
      if (data.ok) {
        const state = data.state || "unknown";
        const rows = Array.isArray(data.interfaces) ? data.interfaces.length : 0;
        setBadge(state, state === "enabled" ? "Enabled" : state === "disabled" ? "Disabled" : "Unknown");
        appendTerminal(`\n[status] IPv6: ${state}; kernel entries: ${rows}`);
      } else {
        appendTerminal(`\n[warn] Status refresh returned error: ${data.error || "unknown"}`);
      }
    } catch (parseError) {
      appendTerminal(`\n[warn] Status JSON parse failed: ${parseError.message}\nraw:\n${statusResult.stdout || ""}`);
    }
  } catch (error) {
    setBadge("error", "Error");
    writeTerminal(`ipv6_manager ${action}`, String(error.message || error));
  } finally {
    endCommand();
  }
}

async function openExternalTest() {
  if (!beginCommand("open test-ipv6.com")) {
    return;
  }

  try {
    const url = "https://test-ipv6.com/";
    const result = await runShell(`am start -a android.intent.action.VIEW -d ${url}`);
    if (result.errno !== 0) {
      throw new Error(`${formatExecResult(result)}\nOpen manually: ${url}`);
    }
    setBadge("pending", "Ready");
    writeTerminal("open test-ipv6.com", formatExecResult(result) || `Opened: ${url}`);
  } catch (error) {
    setBadge("error", "Error");
    writeTerminal("open test-ipv6.com", String(error.message || error));
  } finally {
    endCommand();
  }
}

enableBtn.addEventListener("click", () => runAction("enable"));
disableBtn.addEventListener("click", () => runAction("disable"));
refreshBtn.addEventListener("click", refreshStatus);
externalTestBtn.addEventListener("click", openExternalTest);

loadKernelSU();
