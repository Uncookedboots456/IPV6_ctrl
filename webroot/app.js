const MODULE_SCRIPT = "/data/adb/modules/ipv6_ctrl/scripts/ipv6_manager.sh";

const stateBadge = document.querySelector("#stateBadge");
const output = document.querySelector("#output");
const enableBtn = document.querySelector("#enableBtn");
const disableBtn = document.querySelector("#disableBtn");
const refreshBtn = document.querySelector("#refreshBtn");
const onlineCheckBtn = document.querySelector("#onlineCheckBtn");

let ksuExec = null;

function setBusy(isBusy) {
  enableBtn.disabled = isBusy;
  disableBtn.disabled = isBusy;
  refreshBtn.disabled = isBusy;
  onlineCheckBtn.disabled = isBusy;
}

function writeTerminal(command, text) {
  output.textContent = `$ ${command}\n${text}`;
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

async function refreshStatus() {
  setBusy(true);
  writeTerminal("ipv6_manager json-status", "Running...");
  try {
    const result = await runShell(`sh ${MODULE_SCRIPT} json-status`);
    if (result.errno !== 0) {
      throw new Error(result.stderr || result.stdout || `errno ${result.errno}`);
    }
    const data = JSON.parse(result.stdout || "{}");
    if (!data.ok) {
      throw new Error(data.error || "status failed");
    }
    renderStatus(data);
  } catch (error) {
    setBadge("error", "Error");
    writeTerminal("ipv6_manager json-status", String(error.message || error));
  } finally {
    setBusy(false);
  }
}

async function runAction(action) {
  setBusy(true);
  writeTerminal(`ipv6_manager ${action}`, "Running...");
  try {
    const result = await runShell(`sh ${MODULE_SCRIPT} ${action}`);
    const stdout = (result.stdout || "").trim();
    const stderr = (result.stderr || "").trim();
    if (result.errno !== 0) {
      throw new Error(stderr || stdout || `errno ${result.errno}`);
    }
    writeTerminal(`ipv6_manager ${action}`, stdout || "Done.");
    await refreshStatus();
  } catch (error) {
    setBadge("error", "Error");
    writeTerminal(`ipv6_manager ${action}`, String(error.message || error));
  } finally {
    setBusy(false);
  }
}

async function runOnlineCheck() {
  setBusy(true);
  writeTerminal("ipv6_manager json-online-check", "Running...");
  try {
    const result = await runShell(`sh ${MODULE_SCRIPT} json-online-check`);
    const stdout = (result.stdout || "").trim();
    const stderr = (result.stderr || "").trim();
    if (result.errno !== 0 && !stdout) {
      throw new Error(stderr || `errno ${result.errno}`);
    }
    const data = JSON.parse(stdout || "{}");
    if (!data.ok) {
      throw new Error(data.error || stderr || "online check failed");
    }
    writeTerminal("ipv6_manager online-check", data.output || JSON.stringify(data));
  } catch (error) {
    writeTerminal("ipv6_manager online-check", String(error.message || error));
  } finally {
    setBusy(false);
  }
}

enableBtn.addEventListener("click", () => runAction("enable"));
disableBtn.addEventListener("click", () => runAction("disable"));
refreshBtn.addEventListener("click", refreshStatus);
onlineCheckBtn.addEventListener("click", runOnlineCheck);

loadKernelSU();
