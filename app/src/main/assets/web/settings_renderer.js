/**
 * Settings Renderer for PortTracker AIS
 * Handles fetching, rendering, and saving configuration via Admin API.
 */

var SettingsRenderer = {
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
                    <button class="nav-link" id="tab-sdr" data-bs-toggle="tab" data-bs-target="#pane-sdr" type="button">SDR</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-networking" data-bs-toggle="tab" data-bs-target="#pane-networking" type="button">Networking</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-settings" data-bs-toggle="tab" data-bs-target="#pane-settings" type="button">Data Sharing</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-location" data-bs-toggle="tab" data-bs-target="#pane-location" type="button">Location</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-porttracker" data-bs-toggle="tab" data-bs-target="#pane-porttracker" type="button">porttracker.co</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-database" data-bs-toggle="tab" data-bs-target="#pane-database" type="button">Internal DB</button>
                </li>
                <li class="nav-item">
                    <button class="nav-link" id="tab-control" data-bs-toggle="tab" data-bs-target="#pane-control" type="button">Control</button>
                </li>
            </ul>
            <div class="tab-content p-3 border border-top-0 rounded-bottom bg-white" id="settingsTabContent">
                <div class="tab-pane fade show active" id="pane-status">${this.renderStatusPane()}</div>
                <div class="tab-pane fade" id="pane-sdr">${this.renderSdrPane()}</div>
                <div class="tab-pane fade" id="pane-networking">${this.renderNetworkingPane()}</div>
                <div class="tab-pane fade" id="pane-settings">${this.renderSettingsPane()}</div>
                <div class="tab-pane fade" id="pane-location">${this.renderLocationPane()}</div>
                <div class="tab-pane fade" id="pane-porttracker">${this.renderPorttrackerPane()}</div>
                <div class="tab-pane fade" id="pane-database">${this.renderDatabasePane()}</div>
                <div class="tab-pane fade" id="pane-control">${this.renderControlPane()}</div>
            </div>
            <div class="mt-3 text-end">
                <button class="btn btn-primary" onclick="SettingsRenderer.save()">
                    <i class="bi bi-save"></i> Save & Restart Service
                </button>
            </div>
        `;

        // Hook tab change events for auto-refresh
        const tabEls = container.querySelectorAll('button[data-bs-toggle="tab"]');
        tabEls.forEach(tab => {
            tab.addEventListener('shown.bs.tab', (e) => {
                if (e.target.id === 'tab-sdr') {
                    this.startSdrAutoRefresh();
                } else {
                    this.stopSdrAutoRefresh();
                }
            });
        });
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

    renderSdrPane: function () {
        const serviceRunning = this.bool('service_running');
        const sdrConnected = this.bool('sdr_connected');
        const hasPermission = this.bool('sdr_permission');
        const msgCount = this.val('message_count', 0);
        const deviceName = this.val('sdr_device_name', 'No device');
        const usbDevices = this.val('usb_devices', []);

        const serviceStatusClass = serviceRunning ? 'text-success' : 'text-secondary';
        const serviceStatusText = serviceRunning ? '🟢 Running' : '⚪ Stopped';

        const sdrStatusClass = sdrConnected ? 'text-success' : 'text-danger';
        const sdrStatusText = sdrConnected ? '✅ Connected' : '❌ Not Connected';

        const permissionClass = hasPermission ? 'text-success' : 'text-warning';
        const permissionText = hasPermission ? '✅ Granted' : '⚠️ Not Granted';

        // Build USB device list
        let usbListHtml = '';
        if (Array.isArray(usbDevices) && usbDevices.length > 0) {
            usbListHtml = usbDevices.map(dev => `
                <tr>
                    <td>${dev.name || 'Unknown'}</td>
                    <td><code>${dev.vendorId || '?'}:${dev.productId || '?'}</code></td>
                    <td>${dev.isSDR ? '<span class="badge bg-primary">SDR</span>' : ''}</td>
                </tr>
            `).join('');
        } else {
            usbListHtml = '<tr><td colspan="3" class="text-muted">No USB devices detected</td></tr>';
        }

        return `
            <h5><i class="bi bi-broadcast"></i> SDR Device Status</h5>
            
            <!-- Service Control -->
            <div class="card mb-3">
                <div class="card-body">
                    <div class="d-flex justify-content-between align-items-center">
                        <div>
                            <h6 class="mb-1">SDR Service</h6>
                            <span class="${serviceStatusClass}" id="sdr_service_status">${serviceStatusText}</span>
                        </div>
                        <div class="form-check form-switch">
                            <input class="form-check-input" type="checkbox" id="service_enabled" 
                                ${serviceRunning ? 'checked' : ''} 
                                onchange="SettingsRenderer.toggleService(this.checked)">
                            <label class="form-check-label" for="service_enabled">Enable</label>
                        </div>
                    </div>
                </div>
            </div>
            
            <!-- SDR Device Info -->
            <table class="table table-bordered">
                <tr>
                    <th style="width:40%">AIS Device</th>
                    <td id="sdr_device_name"><strong>${deviceName}</strong></td>
                </tr>
                <tr>
                    <th>SDR Status</th>
                    <td id="sdr_connected">${sdrStatusText}</td>
                </tr>
                <tr>
                    <th>USB Permission</th>
                    <td id="sdr_usb_permission">${permissionText}</td>
                </tr>
                <tr>
                    <th>Messages Received</th>
                    <td><span class="badge bg-info fs-6" id="sdr_msg_count">${msgCount}</span></td>
                </tr>
            </table>
            
            <!-- Connected USB Devices -->
            <h6 class="mt-4"><i class="bi bi-usb-symbol"></i> Connected USB Devices</h6>
            <table class="table table-sm table-striped">
                <thead>
                    <tr>
                        <th>Device Name</th>
                        <th>VID:PID</th>
                        <th>Type</th>
                    </tr>
                </thead>
                <tbody id="usb_device_list">
                    ${usbListHtml}
                </tbody>
            </table>
            
            <button class="btn btn-outline-secondary btn-sm" onclick="SettingsRenderer.refreshSdrStatus()">
                <i class="bi bi-arrow-clockwise"></i> Refresh Status
            </button>
            
            <hr>
            <h5 class="mt-4"><i class="bi bi-gear"></i> SDR Configuration</h5>
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
                <div class="form-text">Frequency correction in Parts Per Million (ppm)</div>
            </div>
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
            <h5><i class="bi bi-share"></i> UDP Outputs</h5>
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

    renderLocationPane: function () {
        const installationType = this.val('installation_type', 'moving');
        const isFixed = installationType === 'fixed';
        const vehicleType = this.val('vehicle_type', 'boat');
        const isBoat = vehicleType === 'boat';

        return `
            <h5><i class="bi bi-geo-alt"></i> Installation Location</h5>
            <p class="text-muted small">Configure where your AIS receiver is installed.</p>
            
            <!-- Installation Type Selection -->
            <div class="mb-3">
                <label class="form-label"><strong>Installation Type</strong></label>
                <div class="btn-group w-100" role="group">
                    <input type="radio" class="btn-check" name="installation_type" id="installation_fixed" value="fixed" 
                        ${isFixed ? 'checked' : ''} onchange="SettingsRenderer.updateLocationFields()">
                    <label class="btn btn-outline-primary" for="installation_fixed">
                        <i class="bi bi-building"></i> Fixed Installation
                    </label>
                    <input type="radio" class="btn-check" name="installation_type" id="installation_moving" value="moving" 
                        ${!isFixed ? 'checked' : ''} onchange="SettingsRenderer.updateLocationFields()">
                    <label class="btn btn-outline-primary" for="installation_moving">
                        <i class="bi bi-arrows-move"></i> Moving Installation
                    </label>
                </div>
            </div>
            
            <!-- Fixed Installation Fields -->
            <div id="fixed_installation_fields" style="display: ${isFixed ? 'block' : 'none'}">
                <div class="card mb-3">
                    <div class="card-header"><i class="bi bi-pin-map"></i> Fixed Location</div>
                    <div class="card-body">
                        <div class="row mb-3">
                            <div class="col-md-6">
                                <label class="form-label">Latitude</label>
                                <input type="number" step="0.000001" class="form-control" id="fixed_latitude" 
                                    value="${this.val('fixed_latitude', '')}" placeholder="e.g. 53.551086">
                            </div>
                            <div class="col-md-6">
                                <label class="form-label">Longitude</label>
                                <input type="number" step="0.000001" class="form-control" id="fixed_longitude" 
                                    value="${this.val('fixed_longitude', '')}" placeholder="e.g. 9.993682">
                            </div>
                        </div>
                        <div id="location_map" style="height: 250px; background: #e9ecef; border-radius: 8px; display: flex; align-items: center; justify-content: center;">
                            <div class="text-center text-muted">
                                <i class="bi bi-map" style="font-size: 2rem;"></i>
                                <p class="mb-1">Interactive Map</p>
                                <button class="btn btn-sm btn-outline-primary" onclick="SettingsRenderer.openMapPicker()">
                                    <i class="bi bi-crosshair"></i> Pick Location on Map
                                </button>
                            </div>
                        </div>
                        <div class="form-text mt-2">Click the map or enter coordinates manually.</div>
                    </div>
                </div>
            </div>
            
            <!-- Moving Installation Fields -->
            <div id="moving_installation_fields" style="display: ${!isFixed ? 'block' : 'none'}">
                <div class="card mb-3">
                    <div class="card-header"><i class="bi bi-truck"></i> Moving Platform</div>
                    <div class="card-body">
                        <div class="mb-3">
                            <label class="form-label">Vehicle Type</label>
                            <select class="form-select" id="vehicle_type" onchange="SettingsRenderer.updateVehicleFields()">
                                <option value="boat" ${vehicleType === 'boat' ? 'selected' : ''}>🚢 Boat</option>
                                <option value="car" ${vehicleType === 'car' ? 'selected' : ''}>🚗 Car</option>
                                <option value="person" ${vehicleType === 'person' ? 'selected' : ''}>🚶 Person</option>
                                <option value="other" ${vehicleType === 'other' ? 'selected' : ''}>📦 Other</option>
                            </select>
                        </div>
                        <div class="mb-3">
                            <label class="form-label">Name</label>
                            <input type="text" class="form-control" id="vehicle_name" 
                                value="${this.val('vehicle_name', '')}" placeholder="e.g. My Boat, Station Alpha">
                        </div>
                        <div class="mb-3" id="mmsi_field" style="display: ${isBoat ? 'block' : 'none'}">
                            <label class="form-label">MMSI</label>
                            <input type="text" class="form-control" id="vessel_mmsi" 
                                value="${this.val('vessel_mmsi', '')}" placeholder="e.g. 211234567" maxlength="9">
                            <div class="form-text">Maritime Mobile Service Identity (9 digits)</div>
                        </div>
                    </div>
                </div>
            </div>
        `;
    },

    renderPorttrackerPane: function () {
        const isConnected = this.bool('porttracker_connected');
        const statusClass = isConnected ? 'text-success' : 'text-danger';
        const statusText = isConnected ? '✅ Connected' : '❌ Not Connected';
        const stationName = this.val('porttracker_station', '');
        const connectUrl = stationName ? `${stationName}.connect.porttracker.co` : '[stationname].connect.porttracker.co';

        return `
            <h5><i class="bi bi-globe"></i> PortTracker.co Integration</h5>
            <p class="text-muted small">Connect your station to the PortTracker.co cloud service for remote monitoring and data sharing.</p>
            
            <div class="mb-3">
                <label class="form-label">Username</label>
                <input type="text" class="form-control" id="porttracker_username" value="${this.val('porttracker_username')}" placeholder="Your porttracker.co username">
            </div>
            <div class="mb-3">
                <label class="form-label">Password</label>
                <input type="password" class="form-control" id="porttracker_password" value="${this.val('porttracker_password')}" placeholder="Your porttracker.co password">
            </div>
            <div class="mb-3">
                <label class="form-label">Station Name</label>
                <input type="text" class="form-control" id="porttracker_station" value="${stationName}" placeholder="my-station-1">
                <div class="form-text">Your station will be accessible at: <code>${connectUrl}</code></div>
            </div>
            <hr>
            <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="porttracker_enabled" ${this.bool('porttracker_enabled') ? 'checked' : ''}>
                <label class="form-check-label" for="porttracker_enabled"><strong>Enable PortTracker Connect</strong></label>
            </div>
            
            <div class="card mb-3">
                <div class="card-body">
                    <div class="d-flex justify-content-between align-items-center">
                        <div>
                            <strong>Status:</strong> <span class="${statusClass}" id="porttracker_status_text">${statusText}</span>
                        </div>
                        <button class="btn btn-primary" onclick="SettingsRenderer.connectPorttracker()" id="porttracker_connect_btn">
                            <i class="bi bi-plug"></i> ${isConnected ? 'Reconnect' : 'Connect'}
                        </button>
                    </div>
                </div>
            </div>
        `;
    },

    renderDatabasePane: function () {
        return `
            <h5><i class="bi bi-database"></i> Internal Database</h5>
            <p class="text-muted small">Store received AIS data locally on the device for later analysis.</p>
            
            <div class="mb-3 form-check form-switch">
                <input class="form-check-input" type="checkbox" id="internal_db_enabled" ${this.bool('internal_db_enabled') ? 'checked' : ''}>
                <label class="form-check-label" for="internal_db_enabled"><strong>Write to Internal Database</strong></label>
            </div>
            <div class="alert alert-secondary">
                <i class="bi bi-info-circle"></i> When enabled, all received AIS messages will be stored in a local SQLite database on the device. This can be useful for offline analysis or data export.
            </div>
        `;
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

        // Collect PortTracker.co settings
        collect('porttracker_username');
        collect('porttracker_password');
        collect('porttracker_station');
        collect('porttracker_enabled', 'bool');

        // Collect Internal DB settings
        collect('internal_db_enabled', 'bool');

        // Collect Location settings
        const installationType = document.getElementById('installation_fixed')?.checked ? 'fixed' : 'moving';
        newConfig['installation_type'] = installationType;
        collect('fixed_latitude');
        collect('fixed_longitude');
        collect('vehicle_type');
        collect('vehicle_name');
        collect('vessel_mmsi');

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

    connectPorttracker: async function () {
        const username = document.getElementById('porttracker_username')?.value || '';
        const password = document.getElementById('porttracker_password')?.value || '';
        const station = document.getElementById('porttracker_station')?.value || '';

        if (!username || !password || !station) {
            alert('Please fill in all PortTracker.co fields (username, password, station name)');
            return;
        }

        const btn = document.getElementById('porttracker_connect_btn');
        const statusText = document.getElementById('porttracker_status_text');

        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span> Connecting...';
        }

        try {
            const response = await fetch('/admin/api/porttracker/connect', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password, station })
            });

            const result = await response.json();

            if (result.success) {
                if (statusText) {
                    statusText.className = 'text-success';
                    statusText.textContent = '✅ Connected';
                }
                if (btn) {
                    btn.innerHTML = '<i class="bi bi-plug"></i> Reconnect';
                }
            } else {
                throw new Error(result.error || 'Connection failed');
            }
        } catch (e) {
            if (statusText) {
                statusText.className = 'text-danger';
                statusText.textContent = '❌ ' + e.message;
            }
            alert('Connection failed: ' + e.message);
        } finally {
            if (btn) {
                btn.disabled = false;
                if (btn.innerHTML.includes('Connecting')) {
                    btn.innerHTML = '<i class="bi bi-plug"></i> Connect';
                }
            }
        }
    },

    toggleService: async function (enable) {
        const endpoint = enable ? '/admin/api/service/start' : '/admin/api/service/stop';
        const statusEl = document.getElementById('sdr_service_status');

        try {
            const response = await fetch(endpoint, { method: 'POST' });
            const result = await response.json();

            if (result.success) {
                if (statusEl) {
                    statusEl.className = enable ? 'text-success' : 'text-secondary';
                    statusEl.textContent = enable ? '🟢 Running' : '⚪ Stopped';
                }
                // Refresh after a short delay to get updated status
                setTimeout(() => this.refreshSdrStatus(), 2000);
            } else {
                throw new Error(result.error || 'Failed to toggle service');
            }
        } catch (e) {
            alert('Error: ' + e.message);
            // Revert checkbox
            document.getElementById('service_enabled').checked = !enable;
        }
    },

    refreshSdrStatus: async function () {
        try {
            const response = await fetch('/admin/api/status');
            const status = await response.json();

            // Update UI elements
            const serviceStatus = document.getElementById('sdr_service_status');
            const msgCount = document.getElementById('sdr_msg_count');
            const serviceCheckbox = document.getElementById('service_enabled');

            if (status.service_running !== undefined) {
                if (serviceStatus) {
                    serviceStatus.className = status.service_running ? 'text-success' : 'text-secondary';
                    serviceStatus.textContent = status.service_running ? '🟢 Running' : '⚪ Stopped';
                }
                if (serviceCheckbox) {
                    serviceCheckbox.checked = status.service_running;
                }
            }

            if (status.message_count !== undefined && msgCount) {
                msgCount.textContent = status.message_count;
            }

            // Update device info
            const deviceName = document.getElementById('sdr_device_name');
            if (deviceName) {
                deviceName.textContent = status.usb_device_name || 'No device';
            }

            const sdrConnected = document.getElementById('sdr_connected');
            if (sdrConnected) {
                const connected = status.usb_device_found === true;
                sdrConnected.innerHTML = connected
                    ? '<span class="text-success">✅ Connected</span>'
                    : '<span class="text-danger">❌ Not Connected</span>';
            }

            const usbPermission = document.getElementById('sdr_usb_permission');
            if (usbPermission) {
                const granted = status.usb_permission_granted === true;
                usbPermission.innerHTML = granted
                    ? '<span class="text-success">✅ Granted</span>'
                    : '<span class="text-warning">⚠️ Not Granted</span>';
            }

            // Update config for full re-render if needed
            this.currentConfig = { ...this.currentConfig, ...status };

        } catch (e) {
            console.error('Failed to refresh SDR status:', e);
        }
    },

    // Auto-refresh SDR status when tab is visible
    _sdrRefreshInterval: null,

    startSdrAutoRefresh: function () {
        this.stopSdrAutoRefresh();
        this.refreshSdrStatus(); // Immediate refresh
        this._sdrRefreshInterval = setInterval(() => this.refreshSdrStatus(), 5000);
    },

    stopSdrAutoRefresh: function () {
        if (this._sdrRefreshInterval) {
            clearInterval(this._sdrRefreshInterval);
            this._sdrRefreshInterval = null;
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
    },

    // Location tab helper functions
    updateLocationFields: function () {
        const isFixed = document.getElementById('installation_fixed')?.checked;
        const fixedFields = document.getElementById('fixed_installation_fields');
        const movingFields = document.getElementById('moving_installation_fields');

        if (fixedFields) fixedFields.style.display = isFixed ? 'block' : 'none';
        if (movingFields) movingFields.style.display = isFixed ? 'none' : 'block';
    },

    updateVehicleFields: function () {
        const vehicleType = document.getElementById('vehicle_type')?.value;
        const mmsiField = document.getElementById('mmsi_field');

        if (mmsiField) {
            mmsiField.style.display = vehicleType === 'boat' ? 'block' : 'none';
        }
    },

    openMapPicker: function () {
        const lat = parseFloat(document.getElementById('fixed_latitude')?.value) || 53.5511;
        const lon = parseFloat(document.getElementById('fixed_longitude')?.value) || 9.9937;

        // Create modal with embedded map
        const modal = document.createElement('div');
        modal.id = 'map-picker-modal';
        modal.style = 'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.8);z-index:9999;display:flex;flex-direction:column;padding:20px;';
        modal.innerHTML = `
            <div style="background:white;border-radius:8px;flex:1;display:flex;flex-direction:column;overflow:hidden;">
                <div style="padding:15px;border-bottom:1px solid #ddd;display:flex;justify-content:space-between;align-items:center;">
                    <h5 style="margin:0;"><i class="bi bi-crosshair"></i> Pick Location</h5>
                    <button class="btn btn-close" onclick="document.getElementById('map-picker-modal').remove()"></button>
                </div>
                <div id="map-picker-container" style="flex:1;position:relative;">
                    <iframe id="map-picker-iframe" 
                        src="https://www.openstreetmap.org/export/embed.html?bbox=${lon - 0.1}%2C${lat - 0.05}%2C${lon + 0.1}%2C${lat + 0.05}&layer=mapnik&marker=${lat}%2C${lon}"
                        style="width:100%;height:100%;border:none;">
                    </iframe>
                    <div style="position:absolute;bottom:10px;left:10px;right:10px;background:white;padding:10px;border-radius:8px;box-shadow:0 2px 10px rgba(0,0,0,0.2);">
                        <div class="row g-2 align-items-center">
                            <div class="col-auto"><label>Lat:</label></div>
                            <div class="col"><input type="number" step="0.000001" class="form-control form-control-sm" id="picker_lat" value="${lat}"></div>
                            <div class="col-auto"><label>Lon:</label></div>
                            <div class="col"><input type="number" step="0.000001" class="form-control form-control-sm" id="picker_lon" value="${lon}"></div>
                            <div class="col-auto">
                                <button class="btn btn-primary btn-sm" onclick="SettingsRenderer.confirmMapLocation()">
                                    <i class="bi bi-check"></i> Confirm
                                </button>
                            </div>
                        </div>
                        <div class="form-text mt-1">Enter coordinates or use browser location button below</div>
                        <button class="btn btn-outline-secondary btn-sm mt-2" onclick="SettingsRenderer.useCurrentLocation()">
                            <i class="bi bi-geo-alt-fill"></i> Use My Current Location
                        </button>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(modal);
    },

    confirmMapLocation: function () {
        const lat = document.getElementById('picker_lat')?.value;
        const lon = document.getElementById('picker_lon')?.value;

        if (lat && lon) {
            document.getElementById('fixed_latitude').value = lat;
            document.getElementById('fixed_longitude').value = lon;
        }

        document.getElementById('map-picker-modal')?.remove();
    },

    useCurrentLocation: function () {
        if (navigator.geolocation) {
            navigator.geolocation.getCurrentPosition(
                (position) => {
                    document.getElementById('picker_lat').value = position.coords.latitude.toFixed(6);
                    document.getElementById('picker_lon').value = position.coords.longitude.toFixed(6);
                },
                (error) => {
                    alert('Could not get location: ' + error.message);
                }
            );
        } else {
            alert('Geolocation is not supported by this browser');
        }
    }
};
