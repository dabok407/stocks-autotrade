/**
 * settings.js — Strategy Groups settings page
 *
 * - Load/save strategy groups via /api/bot/groups
 * - Dynamic group cards with per-group strategy/market/risk settings
 * - Market mutual exclusion between groups
 * - Strategy help popup (? icon → modal with full descriptions)
 * - Strategy Detail Settings popup (⚙ icon → per-strategy interval & EMA)
 * - Tooltip help icons on all fields
 */
;(function(){
  'use strict';

  var req = AutoTrade.req;
  var fmt = AutoTrade.fmt;
  var showToast = AutoTrade.showToast;

  // ── State ──
  var allStrategyOpts = [];     // [{value, label}]
  var allMarketOpts = [];       // [{value, label}]
  var allStrategyData = [];     // full API [{key, label, desc, role, recommendedInterval, emaFilterMode, recommendedEma}]
  var groupInstances = [];      // [{idx, stratMs, marketMs, el, stratIntervals:{}, emaMap:{}}]
  var groupCounter = 0;

  // ── Modal close: global handler for data-modal-close ──
  document.addEventListener('click', function(e) {
    var target = e.target;
    if (target && (target.hasAttribute('data-modal-close') || (target.closest && target.closest('[data-modal-close]')))) {
      var modal = target.closest('.modal');
      if (modal) {
        modal.classList.remove('open');
        modal.setAttribute('aria-hidden', 'true');
        document.body.style.overflow = '';
      }
    }
  });
  document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') {
      var modals = document.querySelectorAll('.modal.open');
      for (var i = 0; i < modals.length; i++) {
        modals[i].classList.remove('open');
        modals[i].setAttribute('aria-hidden', 'true');
      }
      document.body.style.overflow = '';
    }
  });

  // ── Tab Switching ──
  var settingsTabs = document.querySelectorAll('.bt-tab');
  var tabPanels = {
    basic: document.getElementById('settingsTabBasic'),
    krxOpening: document.getElementById('settingsTabKrxOpening'),
    krxAllday: document.getElementById('settingsTabKrxAllday'),
    nyseOpening: document.getElementById('settingsTabNyseOpening'),
    nyseAllday: document.getElementById('settingsTabNyseAllday'),
    krxMorningRush: document.getElementById('settingsTabKrxMorningRush'),
    nyseMorningRush: document.getElementById('settingsTabNyseMorningRush')
  };

  for (var ti = 0; ti < settingsTabs.length; ti++) {
    (function(tab) {
      tab.addEventListener('click', function() {
        var target = tab.getAttribute('data-tab');
        for (var j = 0; j < settingsTabs.length; j++) {
          settingsTabs[j].classList.toggle('active', settingsTabs[j].getAttribute('data-tab') === target);
        }
        for (var key in tabPanels) {
          if (tabPanels[key]) tabPanels[key].style.display = (key === target) ? '' : 'none';
        }
      });
    })(settingsTabs[ti]);
  }

  // ── Init ──
  AutoTrade.initTheme();

  // Logout
  var logoutBtn = document.getElementById('logoutBtn');
  if (logoutBtn) {
    logoutBtn.addEventListener('click', function() {
      fetch(AutoTrade.basePath + '/api/auth/logout', { method: 'POST' }).then(function() {
        window.location.href = AutoTrade.basePath + '/login?logout';
      }).catch(function() {
        window.location.href = AutoTrade.basePath + '/login';
      });
    });
  }

  // ── Load data ──
  init();

  async function init() {
    try {
      var results = await Promise.all([
        req('/api/strategies'),
        req('/api/bot/stocks'),
        req('/api/bot/status'),
        req('/api/bot/groups'),
        req('/api/bot/volume-ranking?topN=100').catch(function() { return []; })
      ]);

      var strategies = results[0];
      var markets = results[1];
      var status = results[2];
      var groups = results[3];
      var volumeRanking = results[4] || [];

      allStrategyData = strategies || [];
      allStrategyOpts = (strategies || []).map(function(s) {
        return { value: s.key || s.name || s.value, label: s.label || s.key || s.name || s.value };
      });

      // DB 종목 맵 (symbol -> displayName)
      var dbMarketMap = {};
      (markets || []).forEach(function(m) {
        dbMarketMap[m.symbol] = m.displayName || m.symbol;
      });

      // 거래대금 TOP100 + DB 종목 병합
      var seen = {};
      allMarketOpts = [];

      // 1) 거래대금 TOP100 (보유종목 제외 표시)
      volumeRanking.forEach(function(item) {
        var sym = item.symbol;
        if (seen[sym]) return;
        seen[sym] = true;
        var name = item.name || dbMarketMap[sym] || sym;
        var label = sym + ' ' + name;
        if (item.excluded) label += ' [보유]';
        allMarketOpts.push({ value: sym, label: label });
      });

      // 2) DB에만 있는 종목 추가 (이미 그룹에 할당된 종목 등)
      (markets || []).forEach(function(m) {
        if (seen[m.symbol]) return;
        seen[m.symbol] = true;
        allMarketOpts.push({ value: m.symbol, label: m.symbol + ' ' + (m.displayName || m.symbol) });
      });

      if (status) {
        var modeEl = document.getElementById('mode');
        if (modeEl && status.mode) modeEl.value = status.mode;
        var capEl = document.getElementById('capital');
        if (capEl && status.capitalKrw) capEl.value = fmt(status.capitalKrw);
      }

      // 미장 기본설정: bot status에서 로드
      if (status) {
        var usModeEl = document.getElementById('usMode');
        if (usModeEl && status.usMode) usModeEl.value = status.usMode;
        var usCapEl = document.getElementById('usCapital');
        if (usCapEl && status.usCapitalKrw) usCapEl.value = fmt(status.usCapitalKrw);
      }

      // 예수금 표시 (API 키가 설정된 경우)
      try {
        var keyResult = await req('/api/keys/kis/test', { method: 'POST', body: '{}' });
        if (keyResult && keyResult.keyConfigured) {
          // 국장 예수금
          var krwDeposit = parseFloat(keyResult.krwBalance) || 0;
          var hint = document.getElementById('balanceHint');
          var amt = document.getElementById('balanceAmt');
          if (hint && amt) {
            amt.textContent = fmt(krwDeposit);
            hint.style.display = 'block';
          }
          // 미장 예수금 (KRW)
          var overseasDeposit = parseFloat(keyResult.overseasDepositKrw) || 0;
          var usHint = document.getElementById('usBalanceHint');
          var usAmt = document.getElementById('usBalanceAmt');
          if (usHint && usAmt) {
            usAmt.textContent = fmt(overseasDeposit);
            usHint.style.display = 'block';
          }
        }
      } catch (e) { /* API 키 미설정 시 무시 */ }

      if (groups && groups.length > 0) {
        for (var i = 0; i < groups.length; i++) {
          addGroupCard(groups[i]);
        }
      } else {
        addGroupCard(null);
      }

      try { AutoTrade.normalizeTooltips(document); } catch (e) {}
    } catch (err) {
      showToast('설정 로드 실패: ' + (err.message || err), 'error');
    }
  }

  // ── Add Group Button ──
  document.getElementById('addGroupBtn').addEventListener('click', function() {
    addGroupCard(null);
  });

  // ── Import from Backtest ──
  document.getElementById('importFromBt').addEventListener('click', function() {
    try {
      var raw = localStorage.getItem('bt_settings_v1');
      if (!raw) { showToast('백테스트에 저장된 설정이 없습니다', 'error'); return; }
      var data = JSON.parse(raw);
      var groups = data && data.groups;
      if (!groups || !groups.length) { showToast('백테스트에 저장된 그룹이 없습니다', 'error'); return; }
      if (!confirm('백테스트 설정 ' + groups.length + '개 그룹을 불러오시겠습니까?\n현재 그룹 설정이 대체됩니다.')) return;
      // 초기화 existing groups
      var container = document.getElementById('groupsContainer');
      container.innerHTML = '';
      groupCounter = 0;
      // Render imported groups
      for (var i = 0; i < groups.length; i++) {
        addGroupCard(groups[i]);
      }
      showToast('백테스트 설정 ' + groups.length + '개 그룹을 불러왔습니다. 설정 저장을 눌러 저장하세요.', 'success');
    } catch (e) {
      showToast('불러오기 실패: ' + (e.message || e), 'error');
    }
  });

  // ── Create Group Card ──
  function addGroupCard(groupData) {
    var idx = groupCounter++;
    var container = document.getElementById('groupsContainer');

    var card = document.createElement('div');
    card.className = 'strategy-group-card';
    card.setAttribute('data-group-idx', idx);

    var defaultName = groupData ? groupData.groupName : ('그룹 ' + (groupInstances.length + 1));
    var collapsed = false;

    // Per-strategy state for this group
    var stratIntervals = {};  // {stratKey: intervalMin}
    var emaMap = {};           // {stratKey: emaPeriod}

    // Restore from saved data
    if (groupData && groupData.strategyIntervalsCsv) {
      var pairs = groupData.strategyIntervalsCsv.split(',');
      for (var pi = 0; pi < pairs.length; pi++) {
        var kv = pairs[pi].trim().split(':');
        if (kv.length === 2 && kv[0] && kv[1]) {
          stratIntervals[kv[0].trim()] = parseInt(kv[1].trim());
        }
      }
    }
    if (groupData && groupData.emaFilterCsv) {
      emaMap = parseEmaMap(groupData.emaFilterCsv);
    }

    card.innerHTML =
      '<div class="group-card-header">' +
        '<div style="display:flex;align-items:center;gap:8px;flex:1;min-width:0">' +
          '<span class="group-collapse-icon" style="cursor:pointer;font-size:14px;user-select:none">&#9660;</span>' +
          '<input type="text" class="group-name-input" value="' + escAttr(defaultName) + '" maxlength="100"/>' +
        '</div>' +
        '<button type="button" class="group-delete-btn pill small" style="color:var(--danger);font-size:12px" title="그룹 삭제">&times;</button>' +
      '</div>' +
      AutoTrade.buildPresetBarHtml() +
      '<div class="group-card-body">' +
        '<div style="font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:.5px;color:var(--primary);margin-bottom:10px;padding-bottom:6px;border-bottom:1px solid var(--border)">매매 설정</div>' +
        '<div class="toolbar">' +
          '<div class="field" style="min-width:260px">' +
            '<label>종목 <span class="help-icon" data-tooltip="이 그룹에서 트레이딩할 종목을 선택합니다.\\n한 종목은 하나의 그룹에만 속할 수 있습니다." aria-label="종목 도움말"></span></label>' +
            '<div class="ms" id="grpMarketMs_' + idx + '">' +
              '<button type="button" class="ms-button"><div class="ms-value"><span class="ms-placeholder">종목 선택...</span><div class="ms-chips"></div></div><span class="ms-caret">&#9662;</span></button>' +
              '<div class="ms-panel"><input class="ms-search" placeholder="종목 검색..."/><div class="ms-list"></div>' +
              '<div class="ms-footer"><button type="button" class="ms-link" data-ms="all">전체 선택</button><button type="button" class="ms-link" data-ms="none">초기화</button></div></div>' +
            '</div>' +
          '</div>' +
          '<div class="field" style="min-width:260px">' +
            '<label class="label-row">' +
              '전략 ' +
              '<span class="help-icon strategy-help-click grp-strat-help" data-tooltip="? 클릭 → 전략 상세 설명" aria-label="전략 설명" tabindex="0" role="button" style="cursor:pointer"></span>' +
              '<button type="button" class="pill small grp-strat-detail-btn" style="font-size:10px;padding:2px 8px;margin-left:4px;vertical-align:middle" title="전략별 인터벌/EMA 상세 설정">⚙ 상세</button>' +
            '</label>' +
            '<div class="ms" id="grpStratMs_' + idx + '">' +
              '<button type="button" class="ms-button"><div class="ms-value"><span class="ms-placeholder">전략 선택...</span><div class="ms-chips"></div></div><span class="ms-caret">&#9662;</span></button>' +
              '<div class="ms-panel"><input class="ms-search" placeholder="전략 검색..."/><div class="ms-list"></div>' +
              '<div class="ms-footer"><button type="button" class="ms-link" data-ms="all">전체 선택</button><button type="button" class="ms-link" data-ms="none">초기화</button></div></div>' +
            '</div>' +
          '</div>' +
          '<div class="field">' +
            '<label>캔들 간격 <span class="help-icon" data-tooltip="기본 캔들 인터벌(분)입니다.\\n⚙ 상세에서 전략별 개별 인터벌을 설정할 수 있습니다." aria-label="Interval help"></span></label>' +
            '<div class="interval-chips grp-interval-chips">' +
              '<button type="button" class="interval-chip" data-val="1">1m</button>' +
              '<button type="button" class="interval-chip" data-val="3">3m</button>' +
              '<button type="button" class="interval-chip" data-val="5">5m</button>' +
              '<button type="button" class="interval-chip" data-val="10">10m</button>' +
              '<button type="button" class="interval-chip" data-val="15">15m</button>' +
              '<button type="button" class="interval-chip" data-val="30">30m</button>' +
              '<button type="button" class="interval-chip" data-val="60">60m</button>' +
              '<button type="button" class="interval-chip active" data-val="240">240m</button>' +
            '</div>' +
            '<input type="hidden" class="grp-interval" value="240"/>' +
          '</div>' +
          '<div class="field" style="min-width:200px">' +
            '<label>주문 크기 <span class="help-icon" data-tooltip="PCT: 자본금의 비율(%) / 고정: 고정 금액(KRW)" aria-label="주문 크기 도움말"></span></label>' +
            '<div style="display:flex;gap:8px;align-items:center">' +
              '<div class="select-wrap" style="width:120px"><select class="select grp-orderMode"><option value="FIXED">고정</option><option value="PCT" selected>% 비율</option></select></div>' +
              '<input class="input grp-orderValue" type="text" value="90" style="width:80px"/>' +
            '</div>' +
          '</div>' +
        '</div>' +
        /* Risk Parameters — collapsible */
        '<div class="risk-toggle">' +
          '<span class="risk-icon">&#9660;</span>' +
          '<span class="risk-label">리스크 파라미터</span>' +
        '</div>' +
        '<div class="risk-body">' +
          '<div class="risk-grid">' +
            '<div class="field"><label>TP (%) <span class="help-icon" data-tooltip="이익실현 비율. 평균매수가 대비 이 비율 이상 상승 시 매도" aria-label="TP help"></span></label><input class="input grp-tp" type="text" value="3.0"/></div>' +
            '<div class="field"><label>SL (%) <span class="help-icon" data-tooltip="손절 비율. 평균매수가 대비 이 비율 이상 하락 시 매도" aria-label="SL help"></span></label><input class="input grp-sl" type="text" value="2.0"/></div>' +
            '<div class="field"><label>최대 추가매수 <span class="help-icon" data-tooltip="최대 추가매수 횟수. 0이면 추가매수 안함" aria-label="Max Add Buys help"></span></label><input class="input grp-maxAdd" type="number" min="0" max="10" value="2"/></div>' +
            '<div class="field"><label>최소 신뢰도 <span class="help-icon" data-tooltip="최소 신뢰도 점수. 이 값 미만 신호 무시. 0=모두 수용" aria-label="최소 신뢰도 도움말"></span></label><input class="input grp-minConf" type="number" min="0" max="10" step="0.5" value="0"/></div>' +
            '<div class="field">' +
              '<label>전략 락 <span class="help-icon" data-tooltip="ON: 진입 전략만 청산 가능\\nOFF: 어떤 전략이든 매도 신호 시 청산" aria-label="Strategy Lock help"></span></label>' +
              '<div style="display:flex;align-items:center;gap:8px;height:42px">' +
                '<button class="switch grp-stratLock" aria-pressed="false"><span class="knob"></span></button>' +
                '<span class="grp-stratLockLabel" style="font-size:13px;color:var(--muted)">OFF</span>' +
              '</div>' +
            '</div>' +
            '<div class="field"><label>타임스탑 (min) <span class="help-icon" data-tooltip="시간 기반 손절(분). 보유시간 초과+손실 시 청산. 0=미사용" aria-label="Time Stop help"></span></label><input class="input grp-timeStop" type="number" min="0" step="30" value="0"/></div>' +
          '</div>' +
        '</div>' +
      '</div>';

    container.appendChild(card);

    // ── Init MultiSelects (no intervalDefaults inside dropdown) ──
    var availableMarkets = getAvailableMarkets(idx);
    var marketMs = AutoTrade.initMultiSelect(
      document.getElementById('grpMarketMs_' + idx),
      {
        placeholder: '종목 선택...',
        options: availableMarkets,
        initial: groupData ? (groupData.markets || []) : [],
        onChange: function() { updateAllMarketOptions(); }
      }
    );

    var stratMs = AutoTrade.initMultiSelect(
      document.getElementById('grpStratMs_' + idx),
      {
        placeholder: '전략 선택...',
        options: allStrategyOpts,
        initial: groupData ? (groupData.strategies || []) : []
      }
    );

    // ── Interval Chips ──
    var intervalChips = card.querySelectorAll('.interval-chip');
    var intervalHidden = card.querySelector('.grp-interval');
    for (var ci = 0; ci < intervalChips.length; ci++) {
      intervalChips[ci].addEventListener('click', function() {
        for (var cj = 0; cj < intervalChips.length; cj++) intervalChips[cj].classList.remove('active');
        this.classList.add('active');
        intervalHidden.value = this.getAttribute('data-val');
      });
    }

    // ── Risk Section Toggle ──
    var riskToggle = card.querySelector('.risk-toggle');
    var riskBody = card.querySelector('.risk-body');
    var riskIcon = card.querySelector('.risk-icon');
    if (riskToggle && riskBody) {
      riskToggle.addEventListener('click', function() {
        var isCollapsed = riskBody.classList.toggle('collapsed');
        if (riskIcon) riskIcon.innerHTML = isCollapsed ? '&#9654;' : '&#9660;';
      });
    }

    // Populate values from groupData
    if (groupData) {
      // Interval chips
      var intVal = String(groupData.candleUnitMin || 240);
      intervalHidden.value = intVal;
      for (var ci2 = 0; ci2 < intervalChips.length; ci2++) {
        intervalChips[ci2].classList.toggle('active', intervalChips[ci2].getAttribute('data-val') === intVal);
      }
      var orderMode = card.querySelector('.grp-orderMode');
      if (orderMode) orderMode.value = groupData.orderSizingMode || 'PCT';
      var orderVal = card.querySelector('.grp-orderValue');
      if (orderVal) orderVal.value = fmt(groupData.orderSizingValue || 90);
      var tpIn = card.querySelector('.grp-tp');
      if (tpIn) tpIn.value = groupData.takeProfitPct != null ? groupData.takeProfitPct : 3.0;
      var slIn = card.querySelector('.grp-sl');
      if (slIn) slIn.value = groupData.stopLossPct != null ? groupData.stopLossPct : 2.0;
      var maxAdd = card.querySelector('.grp-maxAdd');
      if (maxAdd) maxAdd.value = groupData.maxAddBuys != null ? groupData.maxAddBuys : 2;
      var minConf = card.querySelector('.grp-minConf');
      if (minConf) minConf.value = groupData.minConfidence || 0;
      var timeStop = card.querySelector('.grp-timeStop');
      if (timeStop) timeStop.value = groupData.timeStopMinutes || 0;
      var lockBtn = card.querySelector('.grp-stratLock');
      var lockLabel = card.querySelector('.grp-stratLockLabel');
      if (lockBtn && groupData.strategyLock) {
        lockBtn.classList.add('on');
        lockBtn.setAttribute('aria-pressed', 'true');
        if (lockLabel) lockLabel.textContent = 'ON';
      }
    }

    // ── Strategy Lock Toggle ──
    var lockSwitch = card.querySelector('.grp-stratLock');
    var lockLbl = card.querySelector('.grp-stratLockLabel');
    if (lockSwitch) {
      lockSwitch.addEventListener('click', function() {
        var isOn = lockSwitch.classList.toggle('on');
        lockSwitch.setAttribute('aria-pressed', String(isOn));
        if (lockLbl) lockLbl.textContent = isOn ? 'ON' : 'OFF';
      });
    }

    // ── Strategy Help Icon (? → descriptions modal) ──
    var stratHelp = card.querySelector('.grp-strat-help');
    if (stratHelp) {
      stratHelp.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        openStratDescModal();
      });
    }

    // ── Strategy Detail Settings Button (⚙ → per-strategy interval/EMA popup) ──
    var detailBtn = card.querySelector('.grp-strat-detail-btn');
    if (detailBtn) {
      detailBtn.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        openStratDetailModal(inst);
      });
    }

    // ── Collapse/Expand ──
    var collapseIcon = card.querySelector('.group-collapse-icon');
    var cardBody = card.querySelector('.group-card-body');
    if (collapseIcon) {
      collapseIcon.addEventListener('click', function() {
        collapsed = !collapsed;
        cardBody.style.display = collapsed ? 'none' : '';
        collapseIcon.innerHTML = collapsed ? '&#9654;' : '&#9660;';
      });
    }

    // ── Delete ──
    var delBtn = card.querySelector('.group-delete-btn');
    if (delBtn) {
      delBtn.addEventListener('click', function() {
        if (groupInstances.length <= 1) {
          showToast('At least one group is required.', 'error');
          return;
        }
        card.remove();
        groupInstances = groupInstances.filter(function(g) { return g.idx !== idx; });
        updateAllMarketOptions();
      });
    }

    var inst = {
      idx: idx,
      el: card,
      stratMs: stratMs,
      marketMs: marketMs,
      stratIntervals: stratIntervals,
      emaMap: emaMap,
      selectedPreset: (groupData && groupData.selectedPreset) ? groupData.selectedPreset : null
    };
    groupInstances.push(inst);

    // ── Preset Bar (restore saved preset chips) ──
    AutoTrade.bindPresetBar(card, inst);
    if (inst.selectedPreset) {
      AutoTrade.restorePresetChips(card, inst.selectedPreset);
    }

    try { AutoTrade.normalizeTooltips(card); } catch (e) {}
    updateDeleteButtons();
    return inst;
  }

  // ══════════════════════════════════════════════════
  //  Strategy Descriptions Modal (? icon)
  // ══════════════════════════════════════════════════
  function openStratDescModal() {
    req('/api/strategies').then(function(list) {
      var modal = document.getElementById('strategyModal');
      if (!modal) return;
      var body = modal.querySelector('.modal-body');
      var title = modal.querySelector('.modal-title');
      if (title) title.textContent = 'Strategy Descriptions';

      var esc = function(s) { return String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); };
      var nl2br = function(s) { return esc(s).replace(/\n/g, '<br>'); };
      var badge = function(text, bg, fg) { return '<span style="display:inline-block;padding:2px 6px;border-radius:4px;font-size:10px;font-weight:800;background:'+bg+';color:'+fg+';margin-left:4px">'+text+'</span>'; };

      var rows = (list || []).map(function(x) {
        var b = '';
        if (x.role === 'BUY_ONLY') b += badge('\uB9E4\uC218\uC804\uC6A9','rgba(32,201,151,.15)','#20c997');
        if (x.role === 'SELL_ONLY') b += badge('\uB9E4\uB3C4\uC804\uC6A9','rgba(255,77,109,.15)','#ff4d6d');
        if (x.role === 'SELF_CONTAINED') b += badge('\uC790\uAE09\uC790\uC871','rgba(43,118,255,.15)','#2b76ff');
        if (x.recommendedInterval > 0) {
          var lbl = x.recommendedInterval >= 60 ? (x.recommendedInterval/60)+'h' : x.recommendedInterval+'m';
          b += badge(lbl+'\uBD09','rgba(255,193,7,.15)','#ffc107');
        }
        if (x.emaFilterMode === 'CONFIGURABLE' && x.recommendedEma > 0) b += badge('EMA'+x.recommendedEma,'rgba(43,118,255,.15)','#4dabf7');
        if (x.emaFilterMode === 'INTERNAL') b += badge('\uC790\uCCB4EMA','rgba(171,71,188,.15)','#ab47bc');
        return '<tr><td>'+esc(x.label)+b+'</td><td>'+nl2br(x.desc||'')+'</td></tr>';
      }).join('');

      if (body) body.innerHTML = '<table class="tooltip-table"><colgroup><col style="width:30%"/><col style="width:70%"/></colgroup><tbody>'+rows+'</tbody></table>';
      modal.classList.add('open');
      modal.setAttribute('aria-hidden', 'false');
      document.body.style.overflow = 'hidden';
    }).catch(function() {});
  }

  // ══════════════════════════════════════════════════
  //  Strategy Detail Settings Modal (⚙ icon)
  // ══════════════════════════════════════════════════
  var currentDetailInst = null;
  var INTERVAL_OPTIONS = [
    {v:0,l:'기본값'},{v:1,l:'1m'},{v:3,l:'3m'},{v:5,l:'5m'},{v:10,l:'10m'},
    {v:15,l:'15m'},{v:30,l:'30m'},{v:60,l:'1h'},{v:120,l:'2h'},{v:240,l:'4h'}
  ];

  function openStratDetailModal(inst) {
    currentDetailInst = inst;
    var modal = document.getElementById('stratDetailModal');
    if (!modal) return;
    var body = modal.querySelector('.modal-body');
    if (!body) return;

    var selected = inst.stratMs.getSelected();
    if (selected.length === 0) {
      showToast('먼저 전략을 선택해주세요.', 'error');
      return;
    }

    // Build strategy data lookup
    var dataMap = {};
    for (var i = 0; i < allStrategyData.length; i++) {
      dataMap[allStrategyData[i].key] = allStrategyData[i];
    }

    var html = '<div style="margin-bottom:12px;font-size:13px;color:var(--muted)">선택된 전략별로 개별 캔들 인터벌과 EMA 필터를 설정합니다.<br>인터벌 "기본값" = 그룹의 Interval 설정을 따릅니다. EMA 0=OFF</div>';

    // Per-strategy table with Interval + EMA columns
    var hasEmaCol = false;
    for (var ci = 0; ci < selected.length; ci++) {
      var csd = dataMap[selected[ci]] || {};
      if (csd.emaFilterMode === 'CONFIGURABLE') { hasEmaCol = true; break; }
    }

    html += '<table class="tooltip-table" style="width:100%"><thead><tr><th style="text-align:left">전략</th><th style="width:130px;text-align:center">인터벌</th>';
    if (hasEmaCol) html += '<th style="width:100px;text-align:center">EMA</th>';
    html += '</tr></thead><tbody>';
    for (var si = 0; si < selected.length; si++) {
      var skey = selected[si];
      var sd = dataMap[skey] || {};
      var slabel = sd.label || skey;
      var recommended = sd.recommendedInterval || 0;
      var currentVal = inst.stratIntervals[skey] || 0;

      var options = '';
      for (var oi = 0; oi < INTERVAL_OPTIONS.length; oi++) {
        var o = INTERVAL_OPTIONS[oi];
        var sel = (currentVal === o.v) ? ' selected' : '';
        options += '<option value="'+o.v+'"'+sel+'>'+o.l+'</option>';
      }
      var recIntv = recommended > 0 ? (recommended >= 60 ? (recommended/60)+'h' : recommended+'m') : '';
      html += '<tr><td style="font-size:13px">'+escAttr(slabel)+'</td>';
      html += '<td style="text-align:center;vertical-align:middle"><div class="select-wrap" style="width:110px;display:inline-block"><select class="select sd-intv-select" data-strat="'+escAttr(skey)+'" style="width:100%">'+options+'</select></div>';
      if (recIntv) html += '<div style="font-size:10px;color:var(--primary);margin-top:2px">권장 '+recIntv+'</div>';
      html += '</td>';
      if (hasEmaCol) {
        if (sd.emaFilterMode === 'CONFIGURABLE') {
          var recEma = sd.recommendedEma || 50;
          var emaVal = inst.emaMap[skey] != null ? inst.emaMap[skey] : recEma;
          html += '<td style="text-align:center;vertical-align:middle"><input class="input sd-ema-input" data-strat="'+escAttr(skey)+'" type="number" min="0" max="500" step="10" value="'+emaVal+'" style="width:70px;text-align:center"/>';
          html += '<div style="font-size:10px;color:var(--primary);margin-top:2px">권장 '+recEma+'</div></td>';
        } else if (sd.emaFilterMode === 'INTERNAL') {
          html += '<td style="text-align:center;font-size:11px;color:var(--muted)">자체</td>';
        } else {
          html += '<td style="text-align:center;font-size:11px;color:var(--muted)">-</td>';
        }
      }
      html += '</tr>';
    }
    html += '</tbody></table>';

    body.innerHTML = html;

    modal.classList.add('open');
    modal.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
  }

  function saveStratDetailModal() {
    if (!currentDetailInst) return;
    var modal = document.getElementById('stratDetailModal');
    if (!modal) return;

    // Save per-strategy intervals
    var selects = modal.querySelectorAll('.sd-intv-select');
    for (var i = 0; i < selects.length; i++) {
      var key = selects[i].getAttribute('data-strat');
      var val = parseInt(selects[i].value) || 0;
      if (val > 0) {
        currentDetailInst.stratIntervals[key] = val;
      } else {
        delete currentDetailInst.stratIntervals[key];
      }
    }

    // Save per-strategy EMA
    var emaInputs = modal.querySelectorAll('.sd-ema-input');
    for (var i = 0; i < emaInputs.length; i++) {
      var key = emaInputs[i].getAttribute('data-strat');
      var val = parseInt(emaInputs[i].value) || 0;
      if (val > 0) {
        currentDetailInst.emaMap[key] = val;
      } else {
        delete currentDetailInst.emaMap[key];
      }
    }

    modal.classList.remove('open');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
    showToast('전략 상세 설정이 저장되었습니다.', 'success');
  }

  // Bind save button (delegated)
  document.addEventListener('click', function(e) {
    if (e.target && e.target.id === 'sdSaveBtn') {
      saveStratDetailModal();
    }
  });

  // ── Helpers ──
  function parseEmaMap(csv) {
    var map = {};
    if (!csv) return map;
    csv = String(csv).trim();
    if (csv.indexOf(':') >= 0) {
      var pairs = csv.split(',');
      for (var i = 0; i < pairs.length; i++) {
        var kv = pairs[i].trim().split(':');
        if (kv.length === 2 && kv[0] && kv[1]) {
          var v = parseInt(kv[1].trim());
          if (v > 0) map[kv[0].trim()] = v;
        }
      }
    }
    return map;
  }

  function buildStratIntervalsCsv(inst) {
    var parts = [];
    for (var k in inst.stratIntervals) {
      if (inst.stratIntervals.hasOwnProperty(k) && inst.stratIntervals[k] > 0) {
        parts.push(k + ':' + inst.stratIntervals[k]);
      }
    }
    return parts.join(',');
  }

  function buildEmaFilterCsv(inst) {
    var parts = [];
    for (var k in inst.emaMap) {
      if (inst.emaMap.hasOwnProperty(k) && inst.emaMap[k] > 0) {
        parts.push(k + ':' + inst.emaMap[k]);
      }
    }
    return parts.join(',');
  }

  function escAttr(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // ── Market Mutual Exclusion ──
  function getUsedMarkets(excludeIdx) {
    var used = {};
    for (var i = 0; i < groupInstances.length; i++) {
      if (groupInstances[i].idx === excludeIdx) continue;
      var sel = groupInstances[i].marketMs.getSelected();
      for (var j = 0; j < sel.length; j++) used[sel[j]] = true;
    }
    return used;
  }

  function getAvailableMarkets(forIdx) {
    var used = getUsedMarkets(forIdx);
    return allMarketOpts.filter(function(o) { return !used[o.value]; });
  }

  function updateAllMarketOptions() {
    for (var i = 0; i < groupInstances.length; i++) {
      var inst = groupInstances[i];
      var available = getAvailableMarkets(inst.idx);
      var currentSelected = inst.marketMs.getSelected();
      var optSet = {};
      var opts = [];
      for (var j = 0; j < currentSelected.length; j++) {
        var val = currentSelected[j];
        optSet[val] = true;
        var lbl = val;
        for (var k = 0; k < allMarketOpts.length; k++) {
          if (allMarketOpts[k].value === val) { lbl = allMarketOpts[k].label; break; }
        }
        opts.push({ value: val, label: lbl });
      }
      for (var j = 0; j < available.length; j++) {
        if (!optSet[available[j].value]) {
          opts.push(available[j]);
          optSet[available[j].value] = true;
        }
      }
      if (inst.marketMs.updateOptions) inst.marketMs.updateOptions(opts);
    }
  }

  function updateDeleteButtons() {
    var btns = document.querySelectorAll('.group-delete-btn');
    for (var i = 0; i < btns.length; i++) {
      btns[i].style.display = groupInstances.length <= 1 ? 'none' : '';
    }
  }

  // ── Collect groups from UI ──
  function collectGroups() {
    var groups = [];
    for (var i = 0; i < groupInstances.length; i++) {
      var inst = groupInstances[i];
      var card = inst.el;
      var nameInput = card.querySelector('.group-name-input');
      groups.push({
        groupName: nameInput ? nameInput.value.trim() : ('그룹 ' + (i + 1)),
        markets: inst.marketMs.getSelected(),
        strategies: inst.stratMs.getSelected(),
        candleUnitMin: parseInt(card.querySelector('.grp-interval').value) || 60,
        orderSizingMode: card.querySelector('.grp-orderMode').value || 'PCT',
        orderSizingValue: parseFloat(card.querySelector('.grp-orderValue').value.replace(/,/g, '')) || 90,
        takeProfitPct: parseFloat(card.querySelector('.grp-tp').value) || 0,
        stopLossPct: parseFloat(card.querySelector('.grp-sl').value) || 0,
        maxAddBuys: parseInt(card.querySelector('.grp-maxAdd').value) || 0,
        minConfidence: parseFloat(card.querySelector('.grp-minConf').value) || 0,
        strategyLock: card.querySelector('.grp-stratLock').classList.contains('on'),
        timeStopMinutes: parseInt(card.querySelector('.grp-timeStop').value) || 0,
        strategyIntervalsCsv: buildStratIntervalsCsv(inst),
        emaFilterCsv: buildEmaFilterCsv(inst),
        selectedPreset: inst.selectedPreset || null
      });
    }
    return groups;
  }

  // ── Apply ──
  document.getElementById('applyBtn').addEventListener('click', async function() {
    var applyBtn = document.getElementById('applyBtn');
    applyBtn.disabled = true;
    applyBtn.textContent = 'Applying...';
    try {
      // Apply 국장/미장 기본설정 모드를 각 스캐너에 일괄 반영
      var krMode = document.getElementById('mode');
      if (krMode) {
        var krModeVal = krMode.value;
        ['koMode', 'kaMode', 'kmrMode'].forEach(function(id) {
          var el = document.getElementById(id);
          if (el) el.value = krModeVal;
        });
      }
      var usMode = document.getElementById('usMode');
      if (usMode) {
        var usModeVal = usMode.value;
        ['noMode', 'naMode', 'nmrMode'].forEach(function(id) {
          var el = document.getElementById(id);
          if (el) el.value = usModeVal;
        });
      }

      // Save scanner configs first (always, regardless of strategy groups)
      await saveKrxOpening();
      await saveKrxAllday();
      await saveNyseOpening();
      await saveNyseAllday();
      await saveKrxMorningRush();
      await saveNyseMorningRush();

      var groups = collectGroups();
      // Filter out empty groups (no strategy selected)
      var validGroups = [];
      for (var i = 0; i < groups.length; i++) {
        if (groups[i].strategies.length > 0 && groups[i].markets.length > 0) {
          validGroups.push(groups[i]);
        }
      }

      var mode = document.getElementById('mode').value;
      var capitalRaw = document.getElementById('capital').value.replace(/,/g, '');
      var capital = parseFloat(capitalRaw) || 0;

      // 미장 자본금
      var usCapitalRaw = document.getElementById('usCapital') ? document.getElementById('usCapital').value.replace(/,/g, '') : '500000';
      var usCapital = parseFloat(usCapitalRaw) || 500000;
      var usModeVal = document.getElementById('usMode') ? document.getElementById('usMode').value : 'PAPER';

      // Save bot settings (mode, capital) + 미장 설정
      var settingsBody = { mode: mode, capitalKrw: capital, usMode: usModeVal, usCapitalKrw: usCapital };
      if (validGroups.length > 0) {
        var allStrats = []; var stratSet = {};
        for (var i = 0; i < validGroups.length; i++) {
          var gs = validGroups[i].strategies;
          for (var j = 0; j < gs.length; j++) {
            if (!stratSet[gs[j]]) { allStrats.push(gs[j]); stratSet[gs[j]] = true; }
          }
        }
        settingsBody.strategies = allStrats;
        settingsBody.candleUnitMin = validGroups[0].candleUnitMin;
        settingsBody.orderSizingMode = validGroups[0].orderSizingMode;
        settingsBody.orderSizingValue = validGroups[0].orderSizingValue;
        settingsBody.takeProfitPct = validGroups[0].takeProfitPct;
        settingsBody.stopLossPct = validGroups[0].stopLossPct;
        settingsBody.maxAddBuysGlobal = validGroups[0].maxAddBuys;
        settingsBody.strategyLock = validGroups[0].strategyLock;
        settingsBody.minConfidence = validGroups[0].minConfidence;
        settingsBody.timeStopMinutes = validGroups[0].timeStopMinutes;
      }

      await req('/api/bot/config', { method: 'POST', body: JSON.stringify(settingsBody) });

      await req('/api/bot/groups', { method: 'POST', body: JSON.stringify(validGroups) });

      var msg = '설정 저장 완료!';
      if (validGroups.length > 0) {
        msg += ' (타겟 전략 ' + validGroups.length + '개 + 스캐너)';
      } else {
        msg += ' (스캐너 설정)';
      }
      showToast(msg, 'success');
    } catch (err) {
      showToast('Failed: ' + (err.message || err), 'error');
    } finally {
      applyBtn.disabled = false; applyBtn.textContent = 'Apply';
    }
  });

  // ── API Key Test ──
  var apiTestBtn = document.getElementById('apiTestBtn');
  var apiTestResult = document.getElementById('apiTestResult');
  if (apiTestBtn) {
    apiTestBtn.addEventListener('click', async function() {
      apiTestBtn.disabled = true; apiTestBtn.textContent = 'Testing...';
      var apiTestText = document.getElementById('apiTestResultText');
      if (apiTestResult) {
        apiTestResult.style.display = 'block';
        if (apiTestText) { apiTestText.textContent = 'Testing API key...'; }
      }
      try {
        var res = await req('/api/keys/kis/test', { method: 'POST', body: '{}' });
        var text = '';
        if (!res.keyConfigured) { text = 'API key not configured.'; }
        else {
          text = 'Key: OK\n';
          if (res.accountsOk) text += '예수금: ' + fmt(parseFloat(res.krwBalance) || 0) + '원\n';
          else text += 'Balance: FAIL\n';
          text += 'Order: ' + (res.orderTestOk ? 'OK' : 'FAIL') + '\n';
          if (!res.orderTestOk && res.orderTestError) text += '  Error: ' + res.orderTestError + '\n';
        }
        if (apiTestResult) {
          if (apiTestText) { apiTestText.textContent = text; }
          apiTestResult.style.borderColor = (res.accountsOk && res.orderTestOk) ? 'var(--success)' : 'var(--danger)';
        }
      } catch (err) {
        if (apiTestResult) {
          if (apiTestText) { apiTestText.textContent = 'Error: ' + (err.message || err); }
          apiTestResult.style.borderColor = 'var(--danger)';
        }
      } finally {
        apiTestBtn.disabled = false; apiTestBtn.textContent = 'API Test';
      }
    });
  }

  // ── API Test Result Dismiss ──
  var apiTestResultDismiss = document.getElementById('apiTestResultDismiss');
  if (apiTestResultDismiss && apiTestResult) {
    apiTestResultDismiss.addEventListener('click', function() {
      apiTestResult.style.display = 'none';
    });
  }

  // ── US (NYSE) API Key Test ──
  var usApiTestBtn = document.getElementById('usApiTestBtn');
  var usApiTestResult = document.getElementById('usApiTestResult');
  if (usApiTestBtn) {
    usApiTestBtn.addEventListener('click', async function() {
      usApiTestBtn.disabled = true; usApiTestBtn.textContent = 'Testing...';
      var usApiTestText = document.getElementById('usApiTestResultText');
      if (usApiTestResult) {
        usApiTestResult.style.display = 'block';
        if (usApiTestText) { usApiTestText.textContent = 'Testing API key...'; }
      }
      try {
        var res = await req('/api/keys/kis/test', { method: 'POST', body: '{}' });
        var text = '';
        if (!res.keyConfigured) { text = 'API key not configured.'; }
        else {
          text = 'Key: OK\n';
          if (res.accountsOk) text += '예수금: ' + fmt(parseFloat(res.overseasDepositKrw) || 0) + '원\n';
          else text += 'Balance: FAIL\n';
          text += 'Order: ' + (res.orderTestOk ? 'OK' : 'FAIL') + '\n';
          if (!res.orderTestOk && res.orderTestError) text += '  Error: ' + res.orderTestError + '\n';
        }
        if (usApiTestResult) {
          if (usApiTestText) { usApiTestText.textContent = text; }
          usApiTestResult.style.borderColor = (res.accountsOk && res.orderTestOk) ? 'var(--success)' : 'var(--danger)';
        }
      } catch (err) {
        if (usApiTestResult) {
          if (usApiTestText) { usApiTestText.textContent = 'Error: ' + (err.message || err); }
          usApiTestResult.style.borderColor = 'var(--danger)';
        }
      } finally {
        usApiTestBtn.disabled = false; usApiTestBtn.textContent = 'API 테스트';
      }
    });
  }

  // ── US API Test Result Dismiss ──
  var usApiTestResultDismiss = document.getElementById('usApiTestResultDismiss');
  if (usApiTestResultDismiss && usApiTestResult) {
    usApiTestResultDismiss.addEventListener('click', function() {
      usApiTestResult.style.display = 'none';
    });
  }

  // ═══════════════════════════════════════════
  //  Scanner Toggle Helper
  // ═══════════════════════════════════════════

  function parseHHMM(str) {
    if (!str) return [0, 0];
    var parts = String(str).split(':');
    return [parseInt(parts[0]) || 0, parseInt(parts[1]) || 0];
  }

  function fmtHHMM(h, m) {
    return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0');
  }

  // Toggle state per scanner
  var scannerEnabled = { ko: false, ka: false, no: false, na: false, kmr: false, nmr: false };

  function initScannerToggle(prefix) {
    var toggle = document.getElementById(prefix + 'EnabledToggle');
    if (!toggle) return;
    toggle.addEventListener('click', function() {
      scannerEnabled[prefix] = !scannerEnabled[prefix];
      setScannerToggleUI(prefix);
    });
  }

  function setScannerToggleUI(prefix) {
    var toggle = document.getElementById(prefix + 'EnabledToggle');
    if (!toggle) return;
    var en = !!scannerEnabled[prefix];
    toggle.classList.toggle('on', en);
    toggle.setAttribute('aria-pressed', String(en));
    var label = toggle.querySelector('.bot-toggle-label');
    if (label) label.textContent = en ? 'ON' : 'OFF';
  }

  initScannerToggle('ko');
  initScannerToggle('ka');
  initScannerToggle('no');
  initScannerToggle('na');
  initScannerToggle('kmr');
  initScannerToggle('nmr');

  // ═══════════════════════════════════════════
  //  KRX Opening Scanner
  // ═══════════════════════════════════════════

  async function loadKrxOpening() {
    try {
      var cfg = await req('/api/krx-opening/config', { method: 'GET' });
      scannerEnabled.ko = !!cfg.enabled;
      setScannerToggleUI('ko');
      var el = function(id) { return document.getElementById(id); };
      if (el('koMode')) el('koMode').value = cfg.mode || 'PAPER';
      if (el('koOrderMode')) el('koOrderMode').value = cfg.orderSizingMode || 'PCT';
      if (el('koGlobalCapDisplay') && cfg.globalCapitalKrw) {
        el('koGlobalCapDisplay').textContent = '(현재 Capital: ' + fmt(cfg.globalCapitalKrw) + '원)';
      }
      if (el('koOrderValue')) el('koOrderValue').value = cfg.orderSizingValue || 30;
      if (el('koRangeStart')) el('koRangeStart').value = fmtHHMM(cfg.rangeStartHour, cfg.rangeStartMin);
      if (el('koRangeEnd')) el('koRangeEnd').value = fmtHHMM(cfg.rangeEndHour, cfg.rangeEndMin);
      if (el('koEntryStart')) el('koEntryStart').value = fmtHHMM(cfg.entryStartHour, cfg.entryStartMin);
      if (el('koEntryEnd')) el('koEntryEnd').value = fmtHHMM(cfg.entryEndHour, cfg.entryEndMin);
      if (el('koSessionEnd')) el('koSessionEnd').value = fmtHHMM(cfg.sessionEndHour, cfg.sessionEndMin);
      if (el('koTpAtr')) el('koTpAtr').value = cfg.tpAtrMult || 1.2;
      if (el('koSlPct')) el('koSlPct').value = cfg.slPct || 10;
      if (el('koTrailAtr')) el('koTrailAtr').value = cfg.trailAtrMult || 0.8;
      if (el('koCandleUnit')) el('koCandleUnit').value = String(cfg.candleUnitMin || 5);
      if (el('koTopN')) el('koTopN').value = cfg.topN || 15;
      if (el('koMaxPos')) el('koMaxPos').value = cfg.maxPositions || 3;
      if (el('koBtcFilter')) el('koBtcFilter').value = String(cfg.btcFilterEnabled !== false);
      if (el('koOpenFailed')) el('koOpenFailed').value = String(cfg.openFailedEnabled !== false);
      if (el('koVolMult')) el('koVolMult').value = cfg.volumeMult || 1.5;
      if (el('koBodyRatio')) el('koBodyRatio').value = cfg.minBodyRatio || 0.40;
      if (el('koMinPrice')) el('koMinPrice').value = cfg.minPriceKrw != null ? cfg.minPriceKrw : 20;
      if (el('koExcludeMarkets')) el('koExcludeMarkets').value = cfg.excludeSymbols || '';
    } catch(e) {
      console.warn('KRX Opening config load failed:', e);
    }
  }

  async function saveKrxOpening() {
    var el = function(id) { return document.getElementById(id); };
    var rs = parseHHMM(el('koRangeStart') ? el('koRangeStart').value : '08:00');
    var re = parseHHMM(el('koRangeEnd') ? el('koRangeEnd').value : '08:59');
    var es = parseHHMM(el('koEntryStart') ? el('koEntryStart').value : '09:05');
    var ee = parseHHMM(el('koEntryEnd') ? el('koEntryEnd').value : '10:30');
    var se = parseHHMM(el('koSessionEnd') ? el('koSessionEnd').value : '12:00');

    var body = {
      enabled: scannerEnabled.ko,
      mode: el('koMode') ? el('koMode').value : 'PAPER',
      orderSizingMode: el('koOrderMode') ? el('koOrderMode').value : 'PCT',
      orderSizingValue: parseFloat(el('koOrderValue') ? el('koOrderValue').value : '30') || 30,
      rangeStartHour: rs[0], rangeStartMin: rs[1],
      rangeEndHour: re[0], rangeEndMin: re[1],
      entryStartHour: es[0], entryStartMin: es[1],
      entryEndHour: ee[0], entryEndMin: ee[1],
      sessionEndHour: se[0], sessionEndMin: se[1],
      tpAtrMult: parseFloat(el('koTpAtr') ? el('koTpAtr').value : '1.2') || 1.2,
      slPct: parseFloat(el('koSlPct') ? el('koSlPct').value : '10') || 10,
      trailAtrMult: parseFloat(el('koTrailAtr') ? el('koTrailAtr').value : '0.8') || 0.8,
      candleUnitMin: parseInt(el('koCandleUnit') ? el('koCandleUnit').value : '5') || 5,
      topN: parseInt(el('koTopN') ? el('koTopN').value : '15') || 15,
      maxPositions: parseInt(el('koMaxPos') ? el('koMaxPos').value : '3') || 3,
      btcFilterEnabled: (el('koBtcFilter') ? el('koBtcFilter').value : 'true') === 'true',
      openFailedEnabled: (el('koOpenFailed') ? el('koOpenFailed').value : 'true') === 'true',
      volumeMult: parseFloat(el('koVolMult') ? el('koVolMult').value : '1.5') || 1.5,
      minBodyRatio: parseFloat(el('koBodyRatio') ? el('koBodyRatio').value : '0.40') || 0.40,
      minPriceKrw: parseInt(el('koMinPrice') ? el('koMinPrice').value : '20') || 0,
      excludeSymbols: el('koExcludeMarkets') ? el('koExcludeMarkets').value.trim() : ''
    };
    await req('/api/krx-opening/config', { method: 'POST', body: JSON.stringify(body) });
  }

  // ═══════════════════════════════════════════
  //  KRX AllDay Scanner
  // ═══════════════════════════════════════════

  async function loadKrxAllday() {
    try {
      var cfg = await req('/api/krx-allday/config', { method: 'GET' });
      scannerEnabled.ka = !!cfg.enabled;
      setScannerToggleUI('ka');
      var el = function(id) { return document.getElementById(id); };
      if (el('kaMode')) el('kaMode').value = cfg.mode || 'PAPER';
      if (el('kaOrderMode')) el('kaOrderMode').value = cfg.orderSizingMode || 'PCT';
      if (el('kaGlobalCapDisplay') && cfg.globalCapitalKrw) {
        el('kaGlobalCapDisplay').textContent = '(현재 Capital: ' + fmt(cfg.globalCapitalKrw) + '원)';
      }
      if (el('kaOrderValue')) el('kaOrderValue').value = cfg.orderSizingValue || 20;
      if (el('kaEntryStart')) el('kaEntryStart').value = fmtHHMM(cfg.entryStartHour, cfg.entryStartMin);
      if (el('kaEntryEnd')) el('kaEntryEnd').value = fmtHHMM(cfg.entryEndHour, cfg.entryEndMin);
      if (el('kaSessionEnd')) el('kaSessionEnd').value = fmtHHMM(cfg.sessionEndHour, cfg.sessionEndMin);
      if (el('kaSlPct')) el('kaSlPct').value = cfg.slPct || 1.5;
      if (el('kaTrailAtr')) el('kaTrailAtr').value = cfg.trailAtrMult || 0.8;
      if (el('kaMinConf')) el('kaMinConf').value = cfg.minConfidence || 9.4;
      if (el('kaCandleUnit')) el('kaCandleUnit').value = String(cfg.candleUnitMin || 5);
      if (el('kaTsCandles')) el('kaTsCandles').value = cfg.timeStopCandles || 12;
      if (el('kaTsMinPnl')) el('kaTsMinPnl').value = cfg.timeStopMinPnl || 0.3;
      if (el('kaTopN')) el('kaTopN').value = cfg.topN || 15;
      if (el('kaMaxPos')) el('kaMaxPos').value = cfg.maxPositions || 2;
      if (el('kaBtcFilter')) el('kaBtcFilter').value = String(cfg.btcFilterEnabled !== false);
      if (el('kaVolSurge')) el('kaVolSurge').value = cfg.volumeSurgeMult || 3.0;
      if (el('kaBodyRatio')) el('kaBodyRatio').value = cfg.minBodyRatio || 0.60;
      if (el('kaMinPrice')) el('kaMinPrice').value = cfg.minPriceKrw != null ? cfg.minPriceKrw : 20;
      if (el('kaExcludeMarkets')) el('kaExcludeMarkets').value = cfg.excludeSymbols || '';
      if (el('kaQuickTpEnabled')) el('kaQuickTpEnabled').value = String(cfg.quickTpEnabled !== false);
      if (el('kaQuickTpPct')) el('kaQuickTpPct').value = cfg.quickTpPct || 0.7;
      if (el('kaQuickTpInterval')) el('kaQuickTpInterval').value = cfg.quickTpIntervalSec || 5;
    } catch(e) {
      console.warn('KRX AllDay config load failed:', e);
    }
  }

  async function saveKrxAllday() {
    var el = function(id) { return document.getElementById(id); };
    var es = parseHHMM(el('kaEntryStart') ? el('kaEntryStart').value : '10:35');
    var ee = parseHHMM(el('kaEntryEnd') ? el('kaEntryEnd').value : '14:30');
    var se = parseHHMM(el('kaSessionEnd') ? el('kaSessionEnd').value : '15:20');

    var body = {
      enabled: scannerEnabled.ka,
      mode: el('kaMode') ? el('kaMode').value : 'PAPER',
      orderSizingMode: el('kaOrderMode') ? el('kaOrderMode').value : 'PCT',
      orderSizingValue: parseFloat(el('kaOrderValue') ? el('kaOrderValue').value : '20') || 20,
      entryStartHour: es[0], entryStartMin: es[1],
      entryEndHour: ee[0], entryEndMin: ee[1],
      sessionEndHour: se[0], sessionEndMin: se[1],
      slPct: parseFloat(el('kaSlPct') ? el('kaSlPct').value : '1.5') || 1.5,
      trailAtrMult: parseFloat(el('kaTrailAtr') ? el('kaTrailAtr').value : '0.8') || 0.8,
      minConfidence: parseFloat(el('kaMinConf') ? el('kaMinConf').value : '9.4') || 9.4,
      candleUnitMin: parseInt(el('kaCandleUnit') ? el('kaCandleUnit').value : '5') || 5,
      timeStopCandles: parseInt(el('kaTsCandles') ? el('kaTsCandles').value : '12') || 12,
      timeStopMinPnl: parseFloat(el('kaTsMinPnl') ? el('kaTsMinPnl').value : '0.3') || 0.3,
      topN: parseInt(el('kaTopN') ? el('kaTopN').value : '15') || 15,
      maxPositions: parseInt(el('kaMaxPos') ? el('kaMaxPos').value : '2') || 2,
      btcFilterEnabled: (el('kaBtcFilter') ? el('kaBtcFilter').value : 'true') === 'true',
      volumeSurgeMult: parseFloat(el('kaVolSurge') ? el('kaVolSurge').value : '3.0') || 3.0,
      minBodyRatio: parseFloat(el('kaBodyRatio') ? el('kaBodyRatio').value : '0.60') || 0.60,
      minPriceKrw: parseInt(el('kaMinPrice') ? el('kaMinPrice').value : '20') || 0,
      excludeSymbols: el('kaExcludeMarkets') ? el('kaExcludeMarkets').value.trim() : '',
      quickTpEnabled: (el('kaQuickTpEnabled') ? el('kaQuickTpEnabled').value : 'true') === 'true',
      quickTpPct: parseFloat(el('kaQuickTpPct') ? el('kaQuickTpPct').value : '0.7') || 0.7,
      quickTpIntervalSec: parseInt(el('kaQuickTpInterval') ? el('kaQuickTpInterval').value : '5') || 5
    };
    await req('/api/krx-allday/config', { method: 'POST', body: JSON.stringify(body) });
  }

  // ═══════════════════════════════════════════
  //  NYSE Opening Scanner
  // ═══════════════════════════════════════════

  async function loadNyseOpening() {
    try {
      var cfg = await req('/api/nyse-opening/config', { method: 'GET' });
      scannerEnabled.no = !!cfg.enabled;
      setScannerToggleUI('no');
      var el = function(id) { return document.getElementById(id); };
      if (el('noMode')) el('noMode').value = cfg.mode || 'PAPER';
      if (el('noOrderMode')) el('noOrderMode').value = cfg.orderSizingMode || 'PCT';
      if (el('noGlobalCapDisplay') && cfg.globalCapitalKrw) {
        el('noGlobalCapDisplay').textContent = '(현재 Capital: ' + fmt(cfg.globalCapitalKrw) + '원)';
      }
      if (el('noOrderValue')) el('noOrderValue').value = cfg.orderSizingValue || 30;
      if (el('noRangeStart')) el('noRangeStart').value = fmtHHMM(cfg.rangeStartHour, cfg.rangeStartMin);
      if (el('noRangeEnd')) el('noRangeEnd').value = fmtHHMM(cfg.rangeEndHour, cfg.rangeEndMin);
      if (el('noEntryStart')) el('noEntryStart').value = fmtHHMM(cfg.entryStartHour, cfg.entryStartMin);
      if (el('noEntryEnd')) el('noEntryEnd').value = fmtHHMM(cfg.entryEndHour, cfg.entryEndMin);
      if (el('noSessionEnd')) el('noSessionEnd').value = fmtHHMM(cfg.sessionEndHour, cfg.sessionEndMin);
      if (el('noTpAtr')) el('noTpAtr').value = cfg.tpAtrMult || 1.2;
      if (el('noSlPct')) el('noSlPct').value = cfg.slPct || 10;
      if (el('noTrailAtr')) el('noTrailAtr').value = cfg.trailAtrMult || 0.8;
      if (el('noCandleUnit')) el('noCandleUnit').value = String(cfg.candleUnitMin || 5);
      if (el('noTopN')) el('noTopN').value = cfg.topN || 15;
      if (el('noMaxPos')) el('noMaxPos').value = cfg.maxPositions || 3;
      if (el('noBtcFilter')) el('noBtcFilter').value = String(cfg.btcFilterEnabled !== false);
      if (el('noOpenFailed')) el('noOpenFailed').value = String(cfg.openFailedEnabled !== false);
      if (el('noVolMult')) el('noVolMult').value = cfg.volumeMult || 1.5;
      if (el('noBodyRatio')) el('noBodyRatio').value = cfg.minBodyRatio || 0.40;
      if (el('noMinPrice')) el('noMinPrice').value = cfg.minPriceKrw != null ? cfg.minPriceKrw : 5;
      if (el('noExcludeMarkets')) el('noExcludeMarkets').value = cfg.excludeSymbols || '';
    } catch(e) {
      console.warn('NYSE Opening config load failed:', e);
    }
  }

  async function saveNyseOpening() {
    var el = function(id) { return document.getElementById(id); };
    var rs = parseHHMM(el('noRangeStart') ? el('noRangeStart').value : '09:30');
    var re = parseHHMM(el('noRangeEnd') ? el('noRangeEnd').value : '09:59');
    var es = parseHHMM(el('noEntryStart') ? el('noEntryStart').value : '10:05');
    var ee = parseHHMM(el('noEntryEnd') ? el('noEntryEnd').value : '11:30');
    var se = parseHHMM(el('noSessionEnd') ? el('noSessionEnd').value : '13:00');

    var body = {
      enabled: scannerEnabled.no,
      mode: el('noMode') ? el('noMode').value : 'PAPER',
      orderSizingMode: el('noOrderMode') ? el('noOrderMode').value : 'PCT',
      orderSizingValue: parseFloat(el('noOrderValue') ? el('noOrderValue').value : '30') || 30,
      rangeStartHour: rs[0], rangeStartMin: rs[1],
      rangeEndHour: re[0], rangeEndMin: re[1],
      entryStartHour: es[0], entryStartMin: es[1],
      entryEndHour: ee[0], entryEndMin: ee[1],
      sessionEndHour: se[0], sessionEndMin: se[1],
      tpAtrMult: parseFloat(el('noTpAtr') ? el('noTpAtr').value : '1.2') || 1.2,
      slPct: parseFloat(el('noSlPct') ? el('noSlPct').value : '10') || 10,
      trailAtrMult: parseFloat(el('noTrailAtr') ? el('noTrailAtr').value : '0.8') || 0.8,
      candleUnitMin: parseInt(el('noCandleUnit') ? el('noCandleUnit').value : '5') || 5,
      topN: parseInt(el('noTopN') ? el('noTopN').value : '15') || 15,
      maxPositions: parseInt(el('noMaxPos') ? el('noMaxPos').value : '3') || 3,
      btcFilterEnabled: (el('noBtcFilter') ? el('noBtcFilter').value : 'true') === 'true',
      openFailedEnabled: (el('noOpenFailed') ? el('noOpenFailed').value : 'true') === 'true',
      volumeMult: parseFloat(el('noVolMult') ? el('noVolMult').value : '1.5') || 1.5,
      minBodyRatio: parseFloat(el('noBodyRatio') ? el('noBodyRatio').value : '0.40') || 0.40,
      minPriceKrw: parseInt(el('noMinPrice') ? el('noMinPrice').value : '5') || 0,
      excludeSymbols: el('noExcludeMarkets') ? el('noExcludeMarkets').value.trim() : ''
    };
    await req('/api/nyse-opening/config', { method: 'POST', body: JSON.stringify(body) });
  }

  // ═══════════════════════════════════════════
  //  NYSE AllDay Scanner
  // ═══════════════════════════════════════════

  async function loadNyseAllday() {
    try {
      var cfg = await req('/api/nyse-allday/config', { method: 'GET' });
      scannerEnabled.na = !!cfg.enabled;
      setScannerToggleUI('na');
      var el = function(id) { return document.getElementById(id); };
      if (el('naMode')) el('naMode').value = cfg.mode || 'PAPER';
      if (el('naOrderMode')) el('naOrderMode').value = cfg.orderSizingMode || 'PCT';
      if (el('naGlobalCapDisplay') && cfg.globalCapitalKrw) {
        el('naGlobalCapDisplay').textContent = '(현재 Capital: ' + fmt(cfg.globalCapitalKrw) + '원)';
      }
      if (el('naOrderValue')) el('naOrderValue').value = cfg.orderSizingValue || 20;
      if (el('naEntryStart')) el('naEntryStart').value = fmtHHMM(cfg.entryStartHour, cfg.entryStartMin);
      if (el('naEntryEnd')) el('naEntryEnd').value = fmtHHMM(cfg.entryEndHour, cfg.entryEndMin);
      if (el('naSessionEnd')) el('naSessionEnd').value = fmtHHMM(cfg.sessionEndHour, cfg.sessionEndMin);
      if (el('naSlPct')) el('naSlPct').value = cfg.slPct || 1.5;
      if (el('naTrailAtr')) el('naTrailAtr').value = cfg.trailAtrMult || 0.8;
      if (el('naMinConf')) el('naMinConf').value = cfg.minConfidence || 9.4;
      if (el('naCandleUnit')) el('naCandleUnit').value = String(cfg.candleUnitMin || 5);
      if (el('naTsCandles')) el('naTsCandles').value = cfg.timeStopCandles || 12;
      if (el('naTsMinPnl')) el('naTsMinPnl').value = cfg.timeStopMinPnl || 0.3;
      if (el('naTopN')) el('naTopN').value = cfg.topN || 15;
      if (el('naMaxPos')) el('naMaxPos').value = cfg.maxPositions || 2;
      if (el('naBtcFilter')) el('naBtcFilter').value = String(cfg.btcFilterEnabled !== false);
      if (el('naVolSurge')) el('naVolSurge').value = cfg.volumeSurgeMult || 3.0;
      if (el('naBodyRatio')) el('naBodyRatio').value = cfg.minBodyRatio || 0.60;
      if (el('naMinPrice')) el('naMinPrice').value = cfg.minPriceKrw != null ? cfg.minPriceKrw : 5;
      if (el('naExcludeMarkets')) el('naExcludeMarkets').value = cfg.excludeSymbols || '';
      if (el('naQuickTpEnabled')) el('naQuickTpEnabled').value = String(cfg.quickTpEnabled !== false);
      if (el('naQuickTpPct')) el('naQuickTpPct').value = cfg.quickTpPct || 0.7;
      if (el('naQuickTpInterval')) el('naQuickTpInterval').value = cfg.quickTpIntervalSec || 5;
    } catch(e) {
      console.warn('NYSE AllDay config load failed:', e);
    }
  }

  async function saveNyseAllday() {
    var el = function(id) { return document.getElementById(id); };
    var es = parseHHMM(el('naEntryStart') ? el('naEntryStart').value : '10:35');
    var ee = parseHHMM(el('naEntryEnd') ? el('naEntryEnd').value : '15:00');
    var se = parseHHMM(el('naSessionEnd') ? el('naSessionEnd').value : '15:50');

    var body = {
      enabled: scannerEnabled.na,
      mode: el('naMode') ? el('naMode').value : 'PAPER',
      orderSizingMode: el('naOrderMode') ? el('naOrderMode').value : 'PCT',
      orderSizingValue: parseFloat(el('naOrderValue') ? el('naOrderValue').value : '20') || 20,
      entryStartHour: es[0], entryStartMin: es[1],
      entryEndHour: ee[0], entryEndMin: ee[1],
      sessionEndHour: se[0], sessionEndMin: se[1],
      slPct: parseFloat(el('naSlPct') ? el('naSlPct').value : '1.5') || 1.5,
      trailAtrMult: parseFloat(el('naTrailAtr') ? el('naTrailAtr').value : '0.8') || 0.8,
      minConfidence: parseFloat(el('naMinConf') ? el('naMinConf').value : '9.4') || 9.4,
      candleUnitMin: parseInt(el('naCandleUnit') ? el('naCandleUnit').value : '5') || 5,
      timeStopCandles: parseInt(el('naTsCandles') ? el('naTsCandles').value : '12') || 12,
      timeStopMinPnl: parseFloat(el('naTsMinPnl') ? el('naTsMinPnl').value : '0.3') || 0.3,
      topN: parseInt(el('naTopN') ? el('naTopN').value : '15') || 15,
      maxPositions: parseInt(el('naMaxPos') ? el('naMaxPos').value : '2') || 2,
      btcFilterEnabled: (el('naBtcFilter') ? el('naBtcFilter').value : 'true') === 'true',
      volumeSurgeMult: parseFloat(el('naVolSurge') ? el('naVolSurge').value : '3.0') || 3.0,
      minBodyRatio: parseFloat(el('naBodyRatio') ? el('naBodyRatio').value : '0.60') || 0.60,
      minPriceKrw: parseInt(el('naMinPrice') ? el('naMinPrice').value : '5') || 0,
      excludeSymbols: el('naExcludeMarkets') ? el('naExcludeMarkets').value.trim() : '',
      quickTpEnabled: (el('naQuickTpEnabled') ? el('naQuickTpEnabled').value : 'true') === 'true',
      quickTpPct: parseFloat(el('naQuickTpPct') ? el('naQuickTpPct').value : '0.7') || 0.7,
      quickTpIntervalSec: parseInt(el('naQuickTpInterval') ? el('naQuickTpInterval').value : '5') || 5
    };
    await req('/api/nyse-allday/config', { method: 'POST', body: JSON.stringify(body) });
  }

  // ═══════════════════════════════════════════
  //  KRX Morning Rush Scanner
  // ═══════════════════════════════════════════

  async function loadKrxMorningRush() {
    try {
      var cfg = await req('/api/krx-morning-rush/config', { method: 'GET' });
      scannerEnabled.kmr = !!cfg.enabled;
      setScannerToggleUI('kmr');
      var el = function(id) { return document.getElementById(id); };
      if (el('kmrMode')) el('kmrMode').value = cfg.mode || 'PAPER';
      if (el('kmrOrderMode')) el('kmrOrderMode').value = cfg.orderSizingMode || 'PCT';
      if (el('kmrOrderValue')) el('kmrOrderValue').value = cfg.orderSizingValue || 30;
      if (el('kmrGlobalCapDisplay') && cfg.globalCapitalKrw) {
        el('kmrGlobalCapDisplay').textContent = '(현재 Capital: ' + fmt(cfg.globalCapitalKrw) + '원)';
      }
      if (el('kmrGapThreshold')) el('kmrGapThreshold').value = cfg.gapThresholdPct || 2.5;
      if (el('kmrVolMult')) el('kmrVolMult').value = cfg.volumeMult || 3.0;
      if (el('kmrConfirmCount')) el('kmrConfirmCount').value = cfg.confirmCount || 2;
      if (el('kmrCheckInterval')) el('kmrCheckInterval').value = cfg.checkIntervalSec || 5;
      if (el('kmrEntryDelay')) el('kmrEntryDelay').value = cfg.entryDelaySec || 30;
      if (el('kmrTpPct')) el('kmrTpPct').value = cfg.tpPct || 1.5;
      if (el('kmrSlPct')) el('kmrSlPct').value = cfg.slPct || 1.5;
      if (el('kmrSessionEnd')) el('kmrSessionEnd').value = fmtHHMM(cfg.sessionEndHour, cfg.sessionEndMin);
      if (el('kmrTimeStopMin')) el('kmrTimeStopMin').value = cfg.timeStopMinutes || 30;
      if (el('kmrTopN')) el('kmrTopN').value = cfg.topN || 20;
      if (el('kmrMinVolKrw')) el('kmrMinVolKrw').value = cfg.minVolumeKrw || 10;
      if (el('kmrMinPrice')) el('kmrMinPrice').value = cfg.minPriceKrw != null ? cfg.minPriceKrw : 1000;
      if (el('kmrMaxPos')) el('kmrMaxPos').value = cfg.maxPositions || 3;
      if (el('kmrExcludeMarkets')) el('kmrExcludeMarkets').value = cfg.excludeSymbols || '';
    } catch(e) {
      console.warn('KRX Morning Rush config load failed:', e);
    }
  }

  async function saveKrxMorningRush() {
    var el = function(id) { return document.getElementById(id); };
    var se = parseHHMM(el('kmrSessionEnd') ? el('kmrSessionEnd').value : '10:00');

    var body = {
      enabled: scannerEnabled.kmr,
      mode: el('kmrMode') ? el('kmrMode').value : 'PAPER',
      orderSizingMode: el('kmrOrderMode') ? el('kmrOrderMode').value : 'PCT',
      orderSizingValue: parseFloat(el('kmrOrderValue') ? el('kmrOrderValue').value : '30') || 30,
      gapThresholdPct: parseFloat(el('kmrGapThreshold') ? el('kmrGapThreshold').value : '2.5') || 2.5,
      volumeMult: parseFloat(el('kmrVolMult') ? el('kmrVolMult').value : '3.0') || 3.0,
      confirmCount: parseInt(el('kmrConfirmCount') ? el('kmrConfirmCount').value : '2') || 2,
      checkIntervalSec: parseInt(el('kmrCheckInterval') ? el('kmrCheckInterval').value : '5') || 5,
      entryDelaySec: parseInt(el('kmrEntryDelay') ? el('kmrEntryDelay').value : '30') || 30,
      tpPct: parseFloat(el('kmrTpPct') ? el('kmrTpPct').value : '1.5') || 1.5,
      slPct: parseFloat(el('kmrSlPct') ? el('kmrSlPct').value : '1.5') || 1.5,
      sessionEndHour: se[0], sessionEndMin: se[1],
      timeStopMinutes: parseInt(el('kmrTimeStopMin') ? el('kmrTimeStopMin').value : '30') || 30,
      topN: parseInt(el('kmrTopN') ? el('kmrTopN').value : '20') || 20,
      minVolumeKrw: parseInt(el('kmrMinVolKrw') ? el('kmrMinVolKrw').value : '10') || 10,
      minPriceKrw: parseInt(el('kmrMinPrice') ? el('kmrMinPrice').value : '1000') || 0,
      maxPositions: parseInt(el('kmrMaxPos') ? el('kmrMaxPos').value : '3') || 3,
      excludeSymbols: el('kmrExcludeMarkets') ? el('kmrExcludeMarkets').value.trim() : ''
    };
    await req('/api/krx-morning-rush/config', { method: 'POST', body: JSON.stringify(body) });
  }

  // ═══════════════════════════════════════════
  //  NYSE Morning Rush Scanner
  // ═══════════════════════════════════════════

  async function loadNyseMorningRush() {
    try {
      var cfg = await req('/api/nyse-morning-rush/config', { method: 'GET' });
      scannerEnabled.nmr = !!cfg.enabled;
      setScannerToggleUI('nmr');
      var el = function(id) { return document.getElementById(id); };
      if (el('nmrMode')) el('nmrMode').value = cfg.mode || 'PAPER';
      if (el('nmrOrderMode')) el('nmrOrderMode').value = cfg.orderSizingMode || 'PCT';
      if (el('nmrOrderValue')) el('nmrOrderValue').value = cfg.orderSizingValue || 30;
      if (el('nmrGlobalCapDisplay') && cfg.globalCapitalKrw) {
        el('nmrGlobalCapDisplay').textContent = '(현재 Capital: ' + fmt(cfg.globalCapitalKrw) + '원)';
      }
      if (el('nmrGapThreshold')) el('nmrGapThreshold').value = cfg.gapThresholdPct || 4.0;
      if (el('nmrPmHighBreak')) el('nmrPmHighBreak').value = cfg.pmHighBreakPct || 2.0;
      if (el('nmrVolMult')) el('nmrVolMult').value = cfg.volumeMult || 2.0;
      if (el('nmrCheckInterval')) el('nmrCheckInterval').value = cfg.checkIntervalSec || 5;
      if (el('nmrEntryDelay')) el('nmrEntryDelay').value = cfg.entryDelaySec || 30;
      if (el('nmrTpPct')) el('nmrTpPct').value = cfg.tpPct || 3.0;
      if (el('nmrSlPct')) el('nmrSlPct').value = cfg.slPct || 2.0;
      if (el('nmrSessionEnd')) el('nmrSessionEnd').value = fmtHHMM(cfg.sessionEndHour, cfg.sessionEndMin);
      if (el('nmrTimeStopMin')) el('nmrTimeStopMin').value = cfg.timeStopMinutes || 20;
      if (el('nmrTopN')) el('nmrTopN').value = cfg.topN || 20;
      if (el('nmrMinVolUsd')) el('nmrMinVolUsd').value = cfg.minVolumeUsd || 10;
      if (el('nmrMinPrice')) el('nmrMinPrice').value = cfg.minPriceUsd != null ? cfg.minPriceUsd : 5;
      if (el('nmrMaxPos')) el('nmrMaxPos').value = cfg.maxPositions || 3;
      if (el('nmrExcludeMarkets')) el('nmrExcludeMarkets').value = cfg.excludeSymbols || '';
    } catch(e) {
      console.warn('NYSE Morning Rush config load failed:', e);
    }
  }

  async function saveNyseMorningRush() {
    var el = function(id) { return document.getElementById(id); };
    var se = parseHHMM(el('nmrSessionEnd') ? el('nmrSessionEnd').value : '10:30');

    var body = {
      enabled: scannerEnabled.nmr,
      mode: el('nmrMode') ? el('nmrMode').value : 'PAPER',
      orderSizingMode: el('nmrOrderMode') ? el('nmrOrderMode').value : 'PCT',
      orderSizingValue: parseFloat(el('nmrOrderValue') ? el('nmrOrderValue').value : '30') || 30,
      gapThresholdPct: parseFloat(el('nmrGapThreshold') ? el('nmrGapThreshold').value : '4.0') || 4.0,
      pmHighBreakPct: parseFloat(el('nmrPmHighBreak') ? el('nmrPmHighBreak').value : '2.0') || 2.0,
      volumeMult: parseFloat(el('nmrVolMult') ? el('nmrVolMult').value : '2.0') || 2.0,
      checkIntervalSec: parseInt(el('nmrCheckInterval') ? el('nmrCheckInterval').value : '5') || 5,
      entryDelaySec: parseInt(el('nmrEntryDelay') ? el('nmrEntryDelay').value : '30') || 30,
      tpPct: parseFloat(el('nmrTpPct') ? el('nmrTpPct').value : '3.0') || 3.0,
      slPct: parseFloat(el('nmrSlPct') ? el('nmrSlPct').value : '2.0') || 2.0,
      sessionEndHour: se[0], sessionEndMin: se[1],
      timeStopMinutes: parseInt(el('nmrTimeStopMin') ? el('nmrTimeStopMin').value : '20') || 20,
      topN: parseInt(el('nmrTopN') ? el('nmrTopN').value : '20') || 20,
      minVolumeUsd: parseInt(el('nmrMinVolUsd') ? el('nmrMinVolUsd').value : '10') || 10,
      minPriceUsd: parseInt(el('nmrMinPrice') ? el('nmrMinPrice').value : '5') || 0,
      maxPositions: parseInt(el('nmrMaxPos') ? el('nmrMaxPos').value : '3') || 3,
      excludeSymbols: el('nmrExcludeMarkets') ? el('nmrExcludeMarkets').value.trim() : ''
    };
    await req('/api/nyse-morning-rush/config', { method: 'POST', body: JSON.stringify(body) });
  }

  // Load all scanner configs on page load
  loadKrxOpening();
  loadKrxAllday();
  loadNyseOpening();
  loadNyseAllday();
  loadKrxMorningRush();
  loadNyseMorningRush();

  // ── SSO Partner Button ──
  (function() {
    var ssoBtn = document.getElementById('ssoPartnerBtn');
    if (!ssoBtn) return;
    var bp = (window.AutoTrade && window.AutoTrade.basePath) || '';
    setTimeout(function() {
      fetch(bp + '/api/auth/sso-info', { credentials: 'same-origin' })
        .then(function(r) { return r.json(); })
        .then(function(info) {
          if (info && info.enabled === 'true' && info.partnerUrl) {
            ssoBtn.title = info.partnerLabel || 'Partner';
            ssoBtn.setAttribute('data-tooltip', info.partnerLabel || 'Partner');
            ssoBtn.style.display = '';
            ssoBtn.addEventListener('click', function() {
              var popup = window.open('about:blank', '_blank');
              fetch(bp + '/api/auth/sso-token', { credentials: 'same-origin' })
                .then(function(r) { return r.json(); })
                .then(function(td) {
                  if (td && td.success) {
                    var url = info.partnerUrl + '/api/auth/sso-login?token=' +
                      encodeURIComponent(td.token) + '&username=' + encodeURIComponent(td.username) + '&ts=' + td.timestamp;
                    if (popup && !popup.closed) popup.location.href = url;
                    else window.open(url, '_blank');
                  } else { if (popup && !popup.closed) popup.close(); }
                }).catch(function() { if (popup && !popup.closed) popup.close(); });
            });
          }
        })
        .catch(function() {});
    }, 1000);
  })();

})();
