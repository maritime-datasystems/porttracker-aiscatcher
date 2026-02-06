/**
 * Settings Renderer for PortTracker AIS
 * Handles fetching, rendering, and saving configuration via Admin API.
 */

const SettingsRenderer = {
    currentConfig: {},

    // Initialize the Settings View
    init: async function () {
        const container = document.getElementById('settings-content');
        if (!container) return;

        container.innerHTML = '<div class="text-center p-5"><div class="spinner-border text-primary" role="status"></div><br>Loading Configuration...</div>';

        try {
            const response = await fetch('/admin/api/config');
            if (!response.ok) throw new Error("Failed to fetch config");
            this.currentConfig = await response.json();
            this.render();
        } catch (e) {
            container.innerHTML = `<div class="alert alert-danger">Error loading settings: ${e.message}</div>`;
        }
    },

    // Render the Tabs and Forms
    render: function () {
        const container = document.getElementById('settings-content');
        container.innerHTML = `
            <ul class="nav nav-tabs" id="settingsTabs" role="tablist">
                <li class="nav-item">
                    <button class="nav-link active" id="tab-status" data-bs-toggle="tab" data-bs-target="#pane-status" type="button">Status</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-networking" data-bs-toggle="tab" data-bs-target="#pane-networking" type="button">Networking</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-settings" data-bs-toggle="tab" data-bs-target="#pane-settings" type="button">AIS Config</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-control" data-bs-toggle="tab" data-bs-target="#pane-control" type="button">Control</button>
                </li>
            </ul>
            <div class="tab-content p-3 border border-top-0 rounded-bottom bg-white" id="settingsTabContent">
                <div class="tab-pane fade show active" id="pane-status">${this.renderStatusPane()}</div>
                <div class="tab-pane fade" id="pane-networking">${this.renderNetworkingPane()}</div>
                <div class="tab-pane fade" id="pane-settings">${this.renderSettingsPane()}</div>
                <div class="tab-pane fade" id="pane-control">${this.renderControlPane()}</div>
            </div>
            <div class="mt-3 text-end">
                <button class="btn btn-primary" onclick="SettingsRenderer.save()">
                    <i class="bi bi-save"></i> Save & Restart Service
                </button>
            </div>
        `;
    },

    // --- Pane Renderers ---

    renderStatusPane: function () {
        // This is a static view of the current config values deemed "Status" related
        // Note: Real-time status is handled by the Status Page, this is just config view
        return `
            <h5>Application Info</h5>
            <table class="table table-bordered">
                <tr><th>Version</th><td>${this.val('app_version', 'Unknown')}</td></tr>
                <tr><th>Device Type</th><td>${this.getDeviceTypeName(this.val('device_type', '1'))}</td></tr>
                <tr><th>Web Server Port</th><td>${this.val('pref_local_web_port', '8080')}</td></tr>
            </table>
        `;
    },

    renderNetworkingPane: function () {
        return `
            <h5>Remote Access</h5>
            <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="pref_enable_remote" ${this.bool('pref_enable_remote') ? 'checked' : ''}>
                <label class="form-check-label" for="pref_enable_remote">Enable External Web Portal</label>
            </div>
            <div class="mb-3">
                <label class="form-label">Station Name</label>
                <input type="text" class="form-control" id="pref_station_name" value="${this.val('pref_station_name')}">
                <div class="form-text">Unique name for your station (e.g. my-boat-1)</div>
            </div>
            <hr>
            <h5>Local Network</h5>
             <div class="mb-3">
                <label class="form-label">Local Web Port</label>
                <input type="number" class="form-control" id="pref_local_web_port" value="${this.val('pref_local_web_port', '8080')}">
            </div>
             <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="webviewer_enabled" ${this.bool('webviewer_enabled') ? 'checked' : ''}>
                <label class="form-check-label" for="webviewer_enabled">Enable Web Server</label>
            </div>
        `;
    },

    renderSettingsPane: function () {
        let html = `
            <h5>Hardware</h5>
            <div class="mb-3">
                <label class="form-label">Device Type</label>
                <select class="form-select" id="device_type">
                    <option value="1" ${this.val('device_type') == '1' ? 'selected' : ''}>RTL-SDR</option>
                    <option value="0" ${this.val('device_type') == '0' ? 'selected' : ''}>RTL-TCP</option>
                    <option value="2" ${this.val('device_type') == '2' ? 'selected' : ''}>AirSpy</option>
                    <option value="3" ${this.val('device_type') == '3' ? 'selected' : ''}>AirSpy HF+</option>
                    <option value="4" ${this.val('device_type') == '4' ? 'selected' : ''}>SpyServer</option>
                </select>
            </div>
             <div class="mb-3">
                <label class="form-label">PPM Correction</label>
                <input type="number" class="form-control" id="frequency_correction" value="${this.val('frequency_correction', '0')}">
            </div>
            <hr>
            <h5>UDP Outputs</h5>
        `;

        for (let i = 1; i <= 4; i++) {
            html += `
                <div class="card mb-2">
                    <div class="card-header d-flex justify-content-between align-items-center">
                        <span>UDP Output ${i}</span>
                        <div class="form-check form-switch">
                            <input class="form-check-input" type="checkbox" id="udp${i}_enabled" ${this.bool(`udp${i}_enabled`) ? 'checked' : ''}>
                        </div>
                    </div>
                    <div class="card-body row icon-box">
                        <div class="col-md-8">
                            <label class="form-label">Host</label>
                            <input type="text" class="form-control" id="udp${i}_host" value="${this.val(`udp${i}_host`, '127.0.0.1')}">
                        </div>
                        <div class="col-md-4">
                            <label class="form-label">Port</label>
                            <input type="number" class="form-control" id="udp${i}_port" value="${this.val(`udp${i}_port`, 10109 + i)}">
                        </div>
                    </div>
                </div>
            `;
        }

        html += `
            <hr>
            <h5>GPSD Forwarding</h5>
            <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="gpsd_enabled" ${this.bool('gpsd_enabled') ? 'checked' : ''}>
                <label class="form-check-label" for="gpsd_enabled">Enable GPSD Forwarding</label>
            </div>
             <div class="row">
                <div class="col-md-8">
                    <label class="form-label">GPSD Host</label>
                    <input type="text" class="form-control" id="gpsd_host" value="${this.val('gpsd_host', '127.0.0.1')}">
                </div>
                <div class="col-md-4">
                    <label class="form-label">GPSD Port</label>
                    <input type="number" class="form-control" id="gpsd_port" value="${this.val('gpsd_port', '2947')}">
                </div>
            </div>
        `;

        return html;
    },

    renderControlPane: function () {
        return `
            <div class="d-grid gap-2">
                <button class="btn btn-warning" onclick="SettingsRenderer.restartService()">
                    <i class="bi bi-arrow-clockwise"></i> Restart Service Only
                </button>
                 <div class="alert alert-info mt-2">
                    <b>Note:</b> Saving settings will automatically restart the service. Use this button only if you want to force a restart without saving.
                </div>
            </div>
        `;
    },

    // --- Data Helpers ---

    val: function (key, defaultVal = '') {
        return this.currentConfig[key] !== undefined ? this.currentConfig[key] : defaultVal;
    },

    bool: function (key) {
        return this.currentConfig[key] === true || this.currentConfig[key] === 'true';
    },

    getDeviceTypeName: function (val) {
        const types = { '0': 'RTL-TCP', '1': 'RTL-SDR', '2': 'AirSpy', '3': 'AirSpy HF+', '4': 'SpyServer' };
        return types[val] || 'Unknown';
    },

    // --- Actions ---

    save: async function () {
        const newConfig = { ...this.currentConfig };

        // Helper to collect values
        const collect = (id, type = 'string') => {
            const el = document.getElementById(id);
            if (!el) return;
            if (el.type === 'checkbox') {
                newConfig[id] = el.checked;
            } else {
                newConfig[id] = type === 'int' ? parseInt(el.value) : el.value;
            }
        };

        // Collect Networking
        collect('pref_enable_remote', 'bool');
        collect('pref_station_name');
        collect('pref_local_web_port', 'int');
        collect('webviewer_enabled', 'bool');

        // Collect Settings
        collect('device_type', 'string'); // Saved as string "1" usually
        collect('frequency_correction', 'int');
        collect('gpsd_enabled', 'bool');
        collect('gpsd_host');
        collect('gpsd_port', 'int');

        for (let i = 1; i <= 4; i++) {
            collect(`udp${i}_enabled`, 'bool');
            collect(`udp${i}_host`);
            collect(`udp${i}_port`, 'int');
        }

        // Send to API
        this.showLoading("Saving configuration and restarting service...");

        try {
            const response = await fetch('/admin/api/config', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'postData=' + encodeURIComponent(JSON.stringify(newConfig))
            });

            if (response.ok) {
                setTimeout(() => {
                    window.location.reload();
                }, 5000); // Wait 5s for restart then reload
            } else {
                throw new Error("Save failed");
            }
        } catch (e) {
            alert("Error saving settings: " + e.message);
            this.hideLoading();
        }
    },

    restartService: async function () {
        if (!confirm("Are you sure you want to restart the AIS Service?")) return;

        this.showLoading("Restarting Service...");
        try {
            await fetch('/admin/restart', { method: 'POST' });
            setTimeout(() => {
                window.location.reload();
            }, 5000);
        } catch (e) {
            alert("Error restarting: " + e.message);
            this.hideLoading();
        }
    },

    showLoading: function (msg) {
        // Simple overlay
        const overlay = document.createElement('div');
        overlay.id = 'settings-overlay';
        overlay.style = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.7);z-index:9999;display:flex;justify-content:center;align-items:center;color:white;flex-direction:column';
        overlay.innerHTML = `<div class="spinner-border" role="status"></div><h4 class="mt-3">${msg}</h4>`;
        document.body.appendChild(overlay);
    },

    hideLoading: function () {
        const overlay = document.getElementById('settings-overlay');
        if (overlay) overlay.remove();
    }
};
