// 전역 변수
let projectId = null;
let bcGroups = [];
let crudMatrix = null;
let allBCGroups = [];
let showSharedOnly = false;
let recommendations = null;
let domains = null; // ⭐ NEW
let domainMatrix = null; // ⭐⭐⭐ NEW

// URL에서 projectId 가져오기
function getProjectIdFromURL() {
    const urlParams = new URLSearchParams(window.location.search);
    return urlParams.get('projectId');
}

// 초기화
document.addEventListener('DOMContentLoaded', async () => {
    projectId = getProjectIdFromURL();
    
    if (!projectId) {
        document.getElementById('projectInfo').textContent = 'Project ID가 없습니다';
        return;
    }

    document.getElementById('projectInfo').textContent = `Project ID: ${projectId}`;
    
    // Overview 데이터 로드
    await loadOverview();
    
    // 각 탭 클릭 시 데이터 로드
    // (최초에는 Overview만 로드, 나머지는 탭 클릭 시 로드)
});

// 탭 전환
async function switchTab(tabName) {
    // 모든 탭 비활성화
    document.querySelectorAll('.tab').forEach(tab => tab.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(content => content.classList.remove('active'));
    
    // 선택된 탭 활성화
    event.target.classList.add('active');
    document.getElementById(tabName).classList.add('active');
    
    // 탭별 데이터 로드
    if (tabName === 'bc-groups' && bcGroups.length === 0) {
        await loadBCGroups();
    } else if (tabName === 'crud-matrix' && !crudMatrix) {
        await loadCRUDMatrix();
    } else if (tabName === 'recommendations' && !recommendations) {
        await loadRecommendations();
    } else if (tabName === 'domains' && !domains) { // ⭐ NEW
        await loadDomains();
    } else if (tabName === 'domain-matrix' && !domainMatrix) { // ⭐⭐⭐ NEW
        await loadDomainMatrix();
    }
}

// Overview 데이터 로드 (빠른 정보만)
async function loadOverview() {
    try {
        // RAG 인덱스 상태 확인 (즉시 로드)
        const statusRes = await fetch(`/api/projects/${projectId}/rag/status`);
        if (statusRes.ok) {
            const status = await statusRes.json();
            document.getElementById('indexedChunks').textContent = status.indexedChunks || 0;
            document.getElementById('analysisStatus').textContent = 
                status.status === 'INDEXED' ? '✅ 인덱싱 완료' : '⏳ 인덱싱 대기';
            
            // 컴포넌트 수는 청크 수로 대략 표시
            document.getElementById('totalComponents').textContent = status.indexedChunks || 0;
        }
        
        // BC 수와 테이블 수는 사용자가 해당 탭을 클릭하기 전까지 표시 안 함
        document.getElementById('totalBC').textContent = '-';
        document.getElementById('totalBC').style.fontSize = '1.5em';
        document.getElementById('totalBC').style.color = '#999';
        document.getElementById('totalBC').parentElement.querySelector('.stat-label').textContent = 
            'BC 수 (BC Groups 탭에서 분석)';
        
        document.getElementById('totalTables').textContent = '-';
        document.getElementById('totalTables').style.fontSize = '1.5em';
        document.getElementById('totalTables').style.color = '#999';
        document.getElementById('totalTables').parentElement.querySelector('.stat-label').textContent = 
            '테이블 수 (CRUD Matrix 탭에서 확인)';
        
        // 운영 지표
        document.getElementById('lastAnalysisTime').textContent = '미실행';
        document.getElementById('cacheHitRate').textContent = '-';
        
    } catch (error) {
        console.error('Overview 로드 실패:', error);
    }
}

// BC 그룹 로드
async function loadBCGroups() {
    const loading = document.getElementById('bcLoading');
    const list = document.getElementById('bcList');
    
    loading.style.display = 'block';
    list.style.display = 'none';
    
    try {
        const startTime = Date.now();
        
        const response = await fetch(`/api/projects/${projectId}/rag/analyze-bc-groups`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: 'Controller', maxResults: 100 })
        });
        
        if (!response.ok) {
            throw new Error('BC 그룹 로드 실패');
        }
        
        const data = await response.json();
        bcGroups = data.bcGroups || [];
        allBCGroups = [...bcGroups];
        
        const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
        
        renderBCGroups(bcGroups);
        
        loading.style.display = 'none';
        list.style.display = 'grid';
        
        // Overview 업데이트
        updateOverviewAfterBC(data, elapsed);
        
    } catch (error) {
        loading.innerHTML = `<div class="error">BC 그룹 로드 실패: ${error.message}</div>`;
    }
}

// BC 분석 후 Overview 업데이트
function updateOverviewAfterBC(data, elapsed) {
    document.getElementById('totalBC').textContent = data.bcCount || 0;
    document.getElementById('totalBC').style.fontSize = '2em';
    document.getElementById('totalBC').style.color = '#333';
    document.getElementById('totalBC').parentElement.querySelector('.stat-label').textContent = 'BC 수';
    
    document.getElementById('totalComponents').textContent = data.totalComponents || 0;
    
    document.getElementById('lastAnalysisTime').textContent = `${elapsed}초 전`;
    
    // 캐시 히트율 계산 (임시: elapsed 기준)
    const cacheHitRate = elapsed < 5 ? '99%' : elapsed < 30 ? '50%' : '0%';
    document.getElementById('cacheHitRate').textContent = cacheHitRate;
}

// BC 그룹 렌더링
function renderBCGroups(groups) {
    const list = document.getElementById('bcList');
    list.innerHTML = '';
    
    if (groups.length === 0) {
        list.innerHTML = '<div style="padding: 20px; text-align: center; color: #999;">검색 결과가 없습니다</div>';
        return;
    }
    
    groups.forEach((bc, index) => {
        const item = document.createElement('div');
        item.className = 'bc-item';
        item.onclick = () => toggleBCDetail(index);
        
        const confidenceClass = `confidence-${bc.confidence || 'MEDIUM'}`;
        
        item.innerHTML = `
            <div class="bc-header">
                <div class="bc-name">${escapeHtml(bc.bcName || bc.domain)}</div>
                <span class="bc-confidence ${confidenceClass}">${bc.confidence || 'MEDIUM'}</span>
            </div>
            <div class="bc-stats">
                <span>📦 ${bc.componentCount || 0}개 컴포넌트</span>
                <span>🗂️ ${bc.accessedTables?.length || 0}개 테이블</span>
            </div>
            <div class="bc-detail" id="bc-detail-${index}">
                <div class="detail-section">
                    <div class="detail-label">📌 컴포넌트</div>
                    <div>
                        ${(bc.components || []).map(c => 
                            `<span class="component-tag">${escapeHtml(c.name)} (${c.type})</span>`
                        ).join('')}
                    </div>
                </div>
                <div class="detail-section">
                    <div class="detail-label">🗂️ 접근 테이블</div>
                    <div>
                        ${(bc.accessedTables || []).map(t => 
                            `<span class="table-tag">${escapeHtml(t)}</span>`
                        ).join('')}
                    </div>
                </div>
                ${bc.tableAccessMap ? `
                <div class="detail-section">
                    <div class="detail-label">📋 CRUD 작업</div>
                    <div>
                        ${Object.entries(bc.tableAccessMap).map(([table, access]) => `
                            <div style="margin: 5px 0;">
                                <strong>${escapeHtml(table)}:</strong>
                                ${(access.operations || []).map(op => 
                                    `<span class="crud-badge crud-${op}">${op}</span>`
                                ).join('')}
                            </div>
                        `).join('')}
                    </div>
                </div>
                ` : ''}
                <div class="detail-section">
                    <div class="detail-label">💡 마이크로서비스 제안</div>
                    <div style="color: #666; line-height: 1.6;">
                        ${escapeHtml(bc.microserviceSuggestion || '분석 중...')}
                    </div>
                </div>
            </div>
        `;
        
        list.appendChild(item);
    });
}

// BC 상세 토글
function toggleBCDetail(index) {
    const detail = document.getElementById(`bc-detail-${index}`);
    const item = detail.closest('.bc-item');
    
    if (detail.classList.contains('show')) {
        detail.classList.remove('show');
        item.classList.remove('selected');
    } else {
        // 다른 항목 닫기
        document.querySelectorAll('.bc-detail').forEach(d => d.classList.remove('show'));
        document.querySelectorAll('.bc-item').forEach(i => i.classList.remove('selected'));
        
        detail.classList.add('show');
        item.classList.add('selected');
    }
}

// BC 그룹 필터링
function filterBCGroups() {
    const search = document.getElementById('bcSearch').value.toLowerCase();
    
    if (!search) {
        renderBCGroups(allBCGroups);
        return;
    }
    
    const filtered = allBCGroups.filter(bc => 
        (bc.bcName || bc.domain || '').toLowerCase().includes(search)
    );
    
    renderBCGroups(filtered);
}

// CRUD Matrix 로드
async function loadCRUDMatrix() {
    const loading = document.getElementById('matrixLoading');
    const container = document.getElementById('matrixContainer');
    
    loading.style.display = 'block';
    container.style.display = 'none';
    
    try {
        const response = await fetch(`/api/projects/${projectId}/rag/crud-matrix`);
        
        if (!response.ok) {
            throw new Error('CRUD Matrix 로드 실패');
        }
        
        crudMatrix = await response.json();
        
        renderCRUDMatrix();
        
        loading.style.display = 'none';
        container.style.display = 'block';
        
        // Overview 업데이트
        updateOverviewAfterMatrix(crudMatrix);
        
    } catch (error) {
        loading.innerHTML = `<div class="error">CRUD Matrix 로드 실패: ${error.message}</div>`;
    }
}

// CRUD Matrix 로드 후 Overview 업데이트
function updateOverviewAfterMatrix(matrix) {
    if (matrix.statistics) {
        document.getElementById('totalTables').textContent = matrix.statistics.totalTables || 0;
        document.getElementById('totalTables').style.fontSize = '2em';
        document.getElementById('totalTables').style.color = '#333';
        document.getElementById('totalTables').parentElement.querySelector('.stat-label').textContent = '테이블 수';
    }
}

// CRUD Matrix 렌더링
function renderCRUDMatrix() {
    const container = document.getElementById('matrixContainer');
    
    if (!crudMatrix || !crudMatrix.services || !crudMatrix.tables) {
        container.innerHTML = '<div style="padding: 20px; text-align: center; color: #999;">데이터가 없습니다</div>';
        return;
    }
    
    // ⭐ 디버깅: 서비스는 있지만 테이블이 없는 경우
    if (crudMatrix.services.length > 0 && crudMatrix.tables.length === 0) {
        container.innerHTML = `
            <div class="error">
                <h3>⚠️ CRUD Matrix 생성 실패</h3>
                <p><strong>문제:</strong> Service 컴포넌트(${crudMatrix.services.length}개)는 발견되었으나, 
                   접근하는 테이블 정보가 없습니다.</p>
                <p><strong>가능한 원인:</strong></p>
                <ul style="text-align: left; margin: 10px 0;">
                    <li>MyBatis XML 파일이 없거나 파싱 실패</li>
                    <li>CodeChunk.tableAccesses 데이터가 비어있음</li>
                    <li>프로젝트가 JPA만 사용 (MyBatis 없음)</li>
                </ul>
                <p><strong>해결 방법:</strong> 
                   서버 로그를 확인하여 MyBatis XML 파싱 상태를 확인하세요.</p>
                <details style="margin-top: 15px; text-align: left;">
                    <summary style="cursor: pointer; font-weight: 600;">디버그 정보</summary>
                    <pre style="background: #f8f9fa; padding: 10px; border-radius: 6px; margin-top: 10px; overflow-x: auto;">
Services: ${crudMatrix.services.join(', ')}
Tables: ${crudMatrix.tables.length}
Statistics: ${JSON.stringify(crudMatrix.statistics, null, 2)}
                    </pre>
                </details>
            </div>
        `;
        return;
    }
    
    let services = crudMatrix.services;
    let tables = crudMatrix.tables;
    
    // 공유 테이블만 보기 필터
    if (showSharedOnly && crudMatrix.statistics?.sharedTables) {
        const sharedTableNames = crudMatrix.statistics.sharedTables.map(st => st.tableName);
        tables = tables.filter(t => sharedTableNames.includes(t));
    }
    
    // 검색 필터
    const search = document.getElementById('matrixSearch').value.toLowerCase();
    if (search) {
        services = services.filter(s => s.toLowerCase().includes(search));
        tables = tables.filter(t => t.toLowerCase().includes(search));
    }
    
    let html = '<table class="matrix-table"><thead><tr><th>Service \\ Table</th>';
    
    // 테이블 헤더
    tables.forEach(table => {
        html += `<th>${escapeHtml(table)}</th>`;
    });
    
    html += '</tr></thead><tbody>';
    
    // 서비스 행
    services.forEach(service => {
        html += `<tr><td><strong>${escapeHtml(service)}</strong></td>`;
        
        tables.forEach(table => {
            const operations = crudMatrix.matrix[service]?.[table];
            
            if (operations && operations.length > 0) {
                html += '<td class="matrix-cell">';
                operations.forEach(op => {
                    html += `<span class="crud-badge crud-${op}">${op}</span> `;
                });
                html += '</td>';
            } else {
                html += '<td class="matrix-cell empty">-</td>';
            }
        });
        
        html += '</tr>';
    });
    
    html += '</tbody></table>';
    
    // 통계 정보 추가
    if (crudMatrix.statistics) {
        const stats = crudMatrix.statistics;
        html += `
            <div style="margin-top: 20px; padding: 15px; background: #f8f9fa; border-radius: 8px;">
                <strong>📊 통계:</strong> 
                서비스 ${stats.totalServices}개 · 
                테이블 ${stats.totalTables}개 · 
                관계 ${stats.totalRelations}개
                ${stats.sharedTables?.length > 0 ? ` · 공유 테이블 ${stats.sharedTables.length}개` : ''}
            </div>
        `;
        
        // 공유 테이블 상세
        if (stats.sharedTables && stats.sharedTables.length > 0) {
            html += `
                <div style="margin-top: 15px;">
                    <strong>⚠️ 공유 테이블 위험도:</strong>
                    <div style="margin-top: 10px;">
                        ${stats.sharedTables.map(st => `
                            <div style="padding: 10px; margin: 5px 0; background: ${
                                st.couplingRisk === 'HIGH' ? '#f8d7da' :
                                st.couplingRisk === 'MEDIUM' ? '#fff3cd' : '#d1ecf1'
                            }; border-radius: 6px;">
                                <strong>${escapeHtml(st.tableName)}</strong> 
                                (${st.accessCount}개 서비스 접근, 위험도: ${st.couplingRisk})
                                <div style="font-size: 0.85em; color: #666; margin-top: 5px;">
                                    접근 서비스: ${st.accessedByServices.join(', ')}
                                </div>
                            </div>
                        `).join('')}
                    </div>
                </div>
            `;
        }
    }
    
    container.innerHTML = html;
}

// 매트릭스 필터
function filterMatrix() {
    if (crudMatrix) {
        renderCRUDMatrix();
    }
}

// 공유 테이블만 보기 토글
function toggleSharedOnly() {
    showSharedOnly = !showSharedOnly;
    const btn = document.getElementById('sharedOnlyBtn');
    
    if (showSharedOnly) {
        btn.classList.add('active');
    } else {
        btn.classList.remove('active');
    }
    
    if (crudMatrix) {
        renderCRUDMatrix();
    }
}

// HTML 이스케이프
function escapeHtml(text) {
    if (text == null) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 추천 로드
async function loadRecommendations() {
    const loading = document.getElementById('recommendationsLoading');
    const container = document.getElementById('recommendationsContainer');
    
    loading.style.display = 'block';
    container.style.display = 'none';
    
    try {
        const response = await fetch(`/api/projects/${projectId}/rag/recommendations`);
        
        if (!response.ok) {
            throw new Error('추천 생성 실패');
        }
        
        recommendations = await response.json();
        
        renderRecommendations();
        
        loading.style.display = 'none';
        container.style.display = 'block';
        
    } catch (error) {
        loading.innerHTML = `<div class="error">추천 생성 실패: ${error.message}</div>`;
    }
}

// 추천 렌더링
function renderRecommendations() {
    const summary = document.getElementById('recommendationsSummary');
    const list = document.getElementById('recommendationsList');
    
    if (!recommendations || !recommendations.recommendations || recommendations.recommendations.length === 0) {
        list.innerHTML = '<div style="padding: 20px; text-align: center; color: #999;">추천 데이터가 없습니다</div>';
        return;
    }
    
    const recs = recommendations.recommendations;
    const summaryData = recommendations.summary || {};
    
    // 요약 렌더링 (⭐ 라벨 수정)
    summary.innerHTML = `
        <div class="summary-grid">
            <div class="summary-box summary-A">
                <div class="summary-box-value">${summaryData.gradeA || 0}</div>
                <div class="summary-box-label">A등급 (분리 우선 검토)</div>
            </div>
            <div class="summary-box summary-B">
                <div class="summary-box-value">${summaryData.gradeB || 0}</div>
                <div class="summary-box-label">B등급 (리팩토링 후 검토)</div>
            </div>
            <div class="summary-box summary-C">
                <div class="summary-box-value">${summaryData.gradeC || 0}</div>
                <div class="summary-box-label">C등급 (통합/모듈화 권장)</div>
            </div>
        </div>
    `;
    
    // 추천 리스트 렌더링
    list.innerHTML = recs.map(rec => `
        <div class="recommendation-item">
            <div class="rec-header">
                <div class="rec-title">${escapeHtml(rec.bcName)}</div>
                <div class="rec-grade grade-${rec.grade}">${rec.grade}</div>
            </div>
            
            <div class="rec-score">${rec.score}점</div>
            
            <div class="rec-message">${escapeHtml(rec.recommendation)}</div>
            
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 15px; margin-bottom: 15px;">
                <div class="rec-section">
                    <div class="rec-section-title">📊 기본 정보</div>
                    <div style="font-size: 0.9em; color: #666;">
                        • 컴포넌트: ${rec.componentCount}개<br>
                        • 소유 테이블: ${rec.ownedTables?.length || 0}개<br>
                        • 공유 테이블: ${rec.sharedTables?.length || 0}개<br>
                        ${rec.cohesionScore !== undefined ? `
                        • 코드 응집도: <strong style="color: ${rec.cohesionScore >= 0.6 ? '#28a745' : rec.cohesionScore >= 0.4 ? '#ffc107' : '#dc3545'}">${(rec.cohesionScore * 100).toFixed(0)}%</strong><br>
                        • 외부 의존: ${rec.externalDependencies || 0}개 (내부: ${rec.internalDependencies || 0}개)<br>
                        ` : ''}
                        • 신뢰도: ${rec.confidence}<br>
                        • 예상 공수: ${rec.estimatedEffort}
                    </div>
                </div>
                
                <div class="rec-section">
                    <div class="rec-section-title">🏆 주요 소유 테이블 (STRONG)</div>
                    <div class="table-ownership-list">
                        ${(rec.ownedTables || [])
                            .filter(t => t.ownership === 'STRONG')
                            .slice(0, 5)
                            .map(t => `
                                <div class="table-ownership-item ownership-${t.ownership}">
                                    <span><strong>${escapeHtml(t.tableName)}</strong></span>
                                    <span>
                                        ${t.operations.map(op => 
                                            `<span class="crud-badge crud-${op}">${op}</span>`
                                        ).join(' ')}
                                        <span style="margin-left: 8px; color: #666;">(${t.crudScore}점)</span>
                                    </span>
                                </div>
                            `).join('') || '<div style="color: #999; font-size: 0.9em;">주요 소유 테이블 없음</div>'}
                    </div>
                </div>
            </div>
            
            ${rec.sharedTables && rec.sharedTables.length > 0 ? `
            <div class="rec-section">
                <div class="rec-section-title">⚠️ 공유 테이블 (${rec.sharedTables.length}개)</div>
                <div>
                    ${rec.sharedTables.slice(0, 5).map(st => {
                        const isReference = st.riskLevel === 'REFERENCE';
                        const isDummy = st.tableType === 'DUMMY';
                        const riskClass = isReference ? 'REFERENCE' : st.riskLevel;
                        const typeLabel = isDummy ? '🔹 더미' : (isReference ? '📋 공통' : '🔴 트랜잭션');
                        
                        return `
                        <div class="risk-item risk-${riskClass}">
                            ${typeLabel} <strong>${escapeHtml(st.tableName)}</strong> 
                            - ${st.sharedByCount}개 BC 공유 ${isReference ? '(공유 허용)' : `(위험도: ${st.riskLevel})`}
                            <div style="font-size: 0.85em; color: #666; margin-top: 3px;">
                                공유 BC: ${st.sharedWith.join(', ')}
                            </div>
                        </div>
                        `;
                    }).join('')}
                    ${rec.sharedTables.length > 5 ? `<div style="color: #999; font-size: 0.85em; margin-top: 5px;">... 외 ${rec.sharedTables.length - 5}개</div>` : ''}
                </div>
            </div>
            ` : ''}
            
            ${rec.topExternalBCs && Object.keys(rec.topExternalBCs).length > 0 ? `
            <div class="rec-section">
                <div class="rec-section-title">🔗 주요 외부 의존 BC</div>
                <div>
                    ${Object.entries(rec.topExternalBCs).map(([bcName, count]) => `
                        <div class="risk-item risk-LOW" style="border-left-color: #17a2b8;">
                            <strong>${escapeHtml(bcName)}</strong> - ${count}회 호출
                        </div>
                    `).join('')}
                </div>
            </div>
            ` : ''}
            
            ${rec.risks && rec.risks.length > 0 ? `
            <div class="rec-section">
                <div class="rec-section-title">🚨 리스크</div>
                <div>
                    ${rec.risks.map(risk => `
                        <div class="risk-item">${escapeHtml(risk)}</div>
                    `).join('')}
                </div>
            </div>
            ` : ''}
            
            ${rec.requiredActions && rec.requiredActions.length > 0 ? `
            <div class="rec-section">
                <div class="rec-section-title">✅ 필수 작업</div>
                <div>
                    ${rec.requiredActions.map(action => `
                        <div class="action-item">${escapeHtml(action)}</div>
                    `).join('')}
                </div>
            </div>
            ` : ''}
        </div>
    `).join('');
}

// ⭐⭐⭐ 도메인 로딩
async function loadDomains() {
    const loading = document.getElementById('domainsLoading');
    const container = document.getElementById('domainsContainer');
    
    try {
        loading.style.display = 'block';
        container.style.display = 'none';
        
        const res = await fetch(`/api/projects/${projectId}/rag/domains`);
        if (!res.ok) {
            throw new Error('도메인 생성 실패: ' + res.status);
        }
        
        domains = await res.json();
        
        loading.style.display = 'none';
        container.style.display = 'block';
        
        renderDomains();
        
    } catch (error) {
        loading.style.display = 'none';
        container.innerHTML = `
            <div style="color: #dc3545; padding: 20px; text-align: center;">
                ❌ 도메인 생성 실패: ${escapeHtml(error.message)}
            </div>
        `;
        container.style.display = 'block';
    }
}

// ⭐⭐⭐ 도메인 렌더링
function renderDomains() {
    const summary = document.getElementById('domainsSummary');
    const list = document.getElementById('domainsList');
    
    if (!domains || domains.length === 0) {
        list.innerHTML = '<div style="color: #999; text-align: center; padding: 20px;">도메인 데이터가 없습니다</div>';
        return;
    }

    // ⭐ Quarantine(backlog) 분리: 마이크로서비스 후보 vs 분석 보류
    const activeDomains  = domains.filter(d => !d.backlog);
    const backlogDomains = domains.filter(d => d.backlog);
    
    // 요약 (Quarantine 제외 카운트)
    const totalBCs = activeDomains.reduce((sum, b) => sum + b.bcCount, 0);
    const totalOwnedTables = new Set(activeDomains.flatMap(b => b.ownedTables || [])).size;
    const backlogBCs = backlogDomains.reduce((sum, b) => sum + b.bcCount, 0);
    
    summary.innerHTML = `
        <div class="summary-grid">
            <div class="summary-box" style="border-left-color: #667eea;">
                <div class="summary-box-value">${activeDomains.length}</div>
                <div class="summary-box-label">추천 마이크로서비스 수</div>
            </div>
            <div class="summary-box" style="border-left-color: #28a745;">
                <div class="summary-box-value">${totalBCs}</div>
                <div class="summary-box-label">총 BC 수 (활성)</div>
            </div>
            <div class="summary-box" style="border-left-color: #17a2b8;">
                <div class="summary-box-value">${totalOwnedTables}</div>
                <div class="summary-box-label">총 소유 테이블 수</div>
            </div>
            ${backlogDomains.length > 0 ? `
            <div class="summary-box" style="border-left-color: #dc3545;">
                <div class="summary-box-value" style="color: #dc3545;">${backlogBCs}</div>
                <div class="summary-box-label">분리 보류 BC (Backlog)</div>
            </div>
            ` : ''}
        </div>
        ${backlogDomains.length > 0 ? `
        <div style="margin-top: 10px; padding: 8px 14px; background: #fff5f5; border-radius: 8px; border-left: 3px solid #dc3545; font-size: 0.83em; color: #721c24;">
            🚧 <strong>${backlogBCs}개 BC</strong>는 CUD 테이블 미검출로 분리 보류 처리됨 —
            MyBatis Mapper XML 재검토 후 Core/Platform/Supporting으로 이동 권장
        </div>
        ` : ''}
    `;
    
    // 활성 도메인 리스트
    list.innerHTML = `
        ${activeDomains.map((domain, index) => renderDomainCard(domain, index)).join('')}
        ${backlogDomains.length > 0 ? `
        <div style="margin: 30px 0 12px; padding: 10px 16px; background: #f8d7da; border-radius: 10px; border-left: 4px solid #dc3545;">
            <div style="font-weight: 700; font-size: 1em; color: #721c24;">🚧 분석 보류 (Backlog)</div>
            <div style="font-size: 0.82em; color: #856404; margin-top: 4px;">
                아래 BC들은 현재 마이크로서비스 후보에서 제외됨. MyBatis Mapper 파싱 수정 후 재분류 필요.
            </div>
        </div>
        ${backlogDomains.map((domain, index) => renderDomainCard(domain, activeDomains.length + index)).join('')}
        ` : ''}
    `;
}

function renderDomainCard(domain, index) {
    // ⭐⭐⭐ 도메인 타입별 색상 및 배지 (Phase 1)
        const domainTypeColors = {
            'Core Domain': '#28a745',
            'Platform Domain': '#007bff',
            'Supporting Domain': '#6c757d',
            'Read/BFF Layer': '#e67e22',
            '분석 필요 (Quarantine)': '#dc3545'
        };
        const domainTypeColor = domainTypeColors[domain.domainType] || '#667eea';
        const domainTypeBadge = domain.domainType ? `
            <span style="background: ${domainTypeColor}; color: white; padding: 4px 12px; border-radius: 12px; font-size: 0.8em; margin-left: 10px; vertical-align: middle;">
                ${domain.domainType}
            </span>
        ` : '';
        
        const isBacklog = domain.backlog === true;
        const cardBorder = isBacklog ? '#dc3545' : '#667eea';
        const headerBg  = isBacklog
            ? 'background: linear-gradient(135deg, #c0392b 0%, #922b21 100%);'
            : 'background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);';
        const backlogBadge = isBacklog
            ? `<span style="background: rgba(255,255,255,0.25); color: white; padding: 2px 10px; border-radius: 10px; font-size: 0.78em; margin-left: 8px; vertical-align: middle; border: 1px solid rgba(255,255,255,0.4);">🚧 분리 보류</span>`
            : '';

        return `
        <div class="rec-card" style="border: 3px solid ${cardBorder}; box-shadow: 0 4px 12px rgba(102, 126, 234, 0.15); margin-bottom: 30px;">
            <div class="rec-header" style="${headerBg} padding: 20px; margin: -1px -1px 20px -1px;">
                <div>
                    <div class="rec-title" style="color: white; font-size: 1.3em; font-weight: 700;">
                        📦 ${escapeHtml(domain.domainName)}${domainTypeBadge}${backlogBadge}
                    </div>
                    <div style="color: rgba(255,255,255,0.9); font-size: 0.95em; margin-top: 8px;">
                        ${isBacklog ? '🚧 분리 보류 (Backlog)' : `도메인 #${domain.domainId}`} · ${domain.bcCount}개 BC 포함
                        ${isBacklog ? ' — 마이크로서비스 후보 제외' : ''}
                    </div>
                </div>
            </div>
            
            <div style="padding: 0 20px;">
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 15px; margin-bottom: 15px;">
                <div class="rec-section">
                    <div class="rec-section-title">🎯 포함된 BC (${domain.bcCount}개)</div>
                    <div style="font-size: 0.9em;">
                        ${(domain.bcNames || []).map(bcName => `
                            <div style="padding: 5px 10px; margin: 5px 0; background: #f8f9fa; border-radius: 6px; border-left: 3px solid #667eea;">
                                ${escapeHtml(bcName)}
                            </div>
                        `).join('')}
                    </div>
                </div>
                
                <div class="rec-section">
                    <div class="rec-section-title">📊 도메인 통계</div>
                    <div style="font-size: 0.9em; color: #666;">
                        • BC 수: ${domain.bcCount}개<br>
                        • 소유 테이블: ${domain.ownedTables?.length || 0}개<br>
                        • 내부 공유 테이블: ${domain.internalSharedTables?.length || 0}개
                    </div>
                </div>
            </div>
            
            ${domain.ownedTables && domain.ownedTables.length > 0 ? `
            <div class="rec-section">
                <div class="rec-section-title">🗄️ 소유 테이블 (${domain.ownedTables.length}개)</div>
                <div style="display: flex; flex-wrap: wrap; gap: 8px;">
                    ${domain.ownedTables.slice(0, 10).map(table => `
                        <span style="padding: 5px 12px; background: #e7f3ff; border-radius: 20px; font-size: 0.85em; color: #0066cc;">
                            ${escapeHtml(table)}
                        </span>
                    `).join('')}
                    ${domain.ownedTables.length > 10 ? 
                        `<span style="color: #999; font-size: 0.85em;">... 외 ${domain.ownedTables.length - 10}개</span>` : ''}
                </div>
            </div>
            ` : ''}
            
            ${domain.internalSharedTables && domain.internalSharedTables.length > 0 ? `
            <div class="rec-section">
                <div class="rec-section-title">🔗 도메인 내부 공유 테이블 (${domain.internalSharedTables.length}개)</div>
                <div style="font-size: 0.85em; color: #666; margin-bottom: 8px;">
                    도메인 내 BC들이 공유하는 테이블 (트랜잭션 조율 필요)
                </div>
                <div style="display: flex; flex-wrap: wrap; gap: 8px;">
                    ${domain.internalSharedTables.slice(0, 10).map(table => `
                        <span style="padding: 5px 12px; background: #fff3cd; border-radius: 20px; font-size: 0.85em; color: #856404;">
                            ${escapeHtml(table)}
                        </span>
                    `).join('')}
                    ${domain.internalSharedTables.length > 10 ? 
                        `<span style="color: #999; font-size: 0.85em;">... 외 ${domain.internalSharedTables.length - 10}개</span>` : ''}
                </div>
            </div>
            ` : ''}
            
            ${domain.risks && domain.risks.length > 0 ? `
            <div class="rec-section">
                <div class="rec-section-title">🚨 리스크</div>
                <div>
                    ${domain.risks.map(risk => `
                        <div class="risk-item">${escapeHtml(risk)}</div>
                    `).join('')}
                </div>
            </div>
            ` : ''}
            
            ${domain.recommendations && domain.recommendations.length > 0 ? `
            <div class="rec-section">
                <div class="rec-section-title">💡 구현 가이드</div>
                <div>
                    ${domain.recommendations.map(rec => `
                        <div class="action-item">${escapeHtml(rec)}</div>
                    `).join('')}
                </div>
            </div>
            ` : ''}

            ${domain.hasAnalysisIncomplete ? `
            <div style="border-left: 3px solid #dc3545; background: #fff5f5; padding: 10px 14px; border-radius: 8px; margin-bottom: 12px;">
                <div style="color: #dc3545; font-weight: 600; font-size: 0.85em; margin-bottom: 4px;">🔴 분석 불완전 (Quarantine)</div>
                <div style="font-size: 0.82em; color: #6c1a1a;">쓰기 키워드가 있으나 CUD 테이블이 탐지되지 않음 → MyBatis Mapper 파싱 누락 또는 동적 SQL 의심</div>
            </div>
            ` : ''}

            ${domain.readOnlyConfirmed ? `
            <div style="border-left: 3px solid #17a2b8; background: #f0fbff; padding: 8px 14px; border-radius: 8px; margin-bottom: 12px; font-size: 0.82em; color: #0c5460;">
                ✅ CUD 없음 확정 (READ_CONFIRMED) — Read/BFF 배치 적합
            </div>
            ` : ''}

            ${domain.domainType === 'Read/BFF Layer' && (domain.readDependencyTables && domain.readDependencyTables.length > 0) ? `
            <div style="margin-bottom: 15px;">
                <div style="font-size: 0.82em; font-weight: 600; color: #17a2b8; margin-bottom: 6px;">
                    📖 Read Dependency Tables (${domain.readDependencyTables.length}개)
                    <span style="font-size: 0.8em; font-weight: 400; color: #888; margin-left: 6px;">— 직접 접근하는 Core 테이블</span>
                </div>
                <div style="display: flex; flex-wrap: wrap; gap: 6px;">
                    ${domain.readDependencyTables.map(t => `
                        <span style="background: #e3f6fc; color: #0c5460; padding: 3px 8px; border-radius: 4px; font-size: 0.78em; font-family: monospace;">${escapeHtml(t)}</span>
                    `).join('')}
                </div>
                ${domain.readModelCandidates && domain.readModelCandidates.length > 0 ? `
                <div style="margin-top: 10px; font-size: 0.8em; font-weight: 600; color: #155724; margin-bottom: 5px;">
                    🎯 Read Model 구축 후보 (${domain.readModelCandidates.length}개)
                    <span style="font-size: 0.8em; font-weight: 400; color: #888; margin-left: 6px;">— 2개 이상 BFF BC가 공유 접근</span>
                </div>
                <div style="display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 4px;">
                    ${domain.readModelCandidates.map(t => `
                        <span style="background: #d4edda; color: #155724; padding: 3px 8px; border-radius: 4px; font-size: 0.78em; font-family: monospace; border: 1px solid #c3e6cb;">★ ${escapeHtml(t)}</span>
                    `).join('')}
                </div>
                <div style="font-size: 0.75em; color: #888; margin-top: 4px;">→ Materialized View 또는 CQRS Read Store 분리 권장</div>
                ` : ''}
            </div>
            ` : ''}

            ${domain.placementReason ? `
            <div style="font-size: 0.78em; color: #888; margin-bottom: 10px; padding: 4px 8px; background: #f8f9fa; border-radius: 4px; font-family: monospace;">
                📌 배치 근거: ${escapeHtml(domain.placementReason)}
            </div>
            ` : ''}

            ${domain.warnings && domain.warnings.length > 0 ? `
            <div class="rec-section" style="border-left: 3px solid #e67e22; background: #fff8f0; padding: 12px; border-radius: 8px; margin-bottom: 15px;">
                <div class="rec-section-title" style="color: #e67e22;">⚠️ 재배치/타입 경고 (${domain.warnings.length}개)</div>
                <div>
                    ${domain.warnings.map(w => `
                        <div style="font-size: 0.85em; color: #7d4e00; padding: 4px 0; white-space: pre-wrap;">${escapeHtml(w)}</div>
                    `).join('')}
                </div>
            </div>
            ` : ''}

            ${/* ===== Read/BFF: Read Dependency 섹션 ===== */
            (domain.domainType === 'Read/BFF Layer' && domain.readDependencyTables && domain.readDependencyTables.length > 0) ? `
            <div class="rec-section" style="border-left: 3px solid #17a2b8; background: #f0fbff; padding: 12px; border-radius: 8px; margin-bottom: 15px;">
                <div class="rec-section-title" style="color: #0c5460; margin-bottom: 8px;">📖 Read Dependency 테이블 (${domain.readDependencyTables.length}개)</div>
                <div style="font-size: 0.82em; color: #555; margin-bottom: 8px;">Core 서비스 테이블을 직접 READ 접근 중 → Event-Driven 또는 API 호출로 전환 검토</div>
                <div style="display: flex; flex-wrap: wrap; gap: 6px;">
                    ${domain.readDependencyTables.map(t => `<span style="background:#d1ecf1; color:#0c5460; padding:2px 8px; border-radius:10px; font-size:0.8em; font-family:monospace;">${escapeHtml(t)}</span>`).join('')}
                </div>
                ${domain.readModelCandidates && domain.readModelCandidates.length > 0 ? `
                <div style="margin-top: 10px; padding-top: 8px; border-top: 1px solid #bee5eb;">
                    <div style="font-size: 0.82em; font-weight: 600; color: #0c5460; margin-bottom: 6px;">🎯 Read Model 구축 후보 (${domain.readModelCandidates.length}개, 복수 BC 조회)</div>
                    <div style="display: flex; flex-wrap: wrap; gap: 6px;">
                        ${domain.readModelCandidates.map(t => `<span style="background:#0c5460; color:#fff; padding:2px 8px; border-radius:10px; font-size:0.8em; font-family:monospace;">${escapeHtml(t)}</span>`).join('')}
                    </div>
                    <div style="font-size: 0.78em; color: #555; margin-top: 4px;">→ Materialized View 또는 별도 Read Store(Redis/ES) 적용 권장</div>
                </div>
                ` : ''}
            </div>
            ` : ''}

            ${/* ===== Quarantine 상세 원인 리포트 ===== */
            domain.hasAnalysisIncomplete && domain.warnings && domain.warnings.some(w => w.includes('━━')) ? `
            <details style="margin-bottom: 15px;" open>
                <summary style="cursor: pointer; padding: 10px; background: #fff5f5; border-radius: 8px; font-size: 0.9em; font-weight: 600; color: #dc3545;">
                    🔬 Mapper 파싱 실패 원인 분석
                </summary>
                <div style="margin-top: 8px; padding: 12px; background: #fff5f5; border-radius: 8px; border-left: 3px solid #dc3545; font-size: 0.82em; font-family: monospace; white-space: pre-wrap; color: #333;">
                    ${domain.warnings.filter(w => w.startsWith('━━') || w.startsWith('  ')).map(w => escapeHtml(w)).join('\n')}
                </div>
            </details>
            ` : ''}

            ${domain.classificationReasons && domain.classificationReasons.length > 0 ? `
            <details style="margin-bottom: 15px;">
                <summary style="cursor: pointer; padding: 10px; background: #f8f9fa; border-radius: 8px; font-size: 0.9em; font-weight: 600; color: #495057;">
                    🔍 분류 근거 보기 (${domain.classificationReasons.length}개)
                </summary>
                <div style="margin-top: 8px; padding: 12px; background: #f8f9fa; border-radius: 8px; border-left: 3px solid #6c757d;">
                    ${domain.classificationReasons.map(reason => `
                        <div style="font-size: 0.85em; color: #555; padding: 3px 0; border-bottom: 1px solid #e9ecef; white-space: pre-wrap;">${escapeHtml(reason)}</div>
                    `).join('')}
                </div>
            </details>
            ` : ''}

            ${domain.bcNames && domain.bcNames.length > 0 ? `
            <details style="margin-bottom: 15px;">
                <summary style="cursor: pointer; padding: 10px; background: #f0f4ff; border-radius: 8px; font-size: 0.9em; font-weight: 600; color: #3a4bcc;">
                    🗺️ BC ↔ 테이블 연결도 보기
                </summary>
                <div style="margin-top: 8px; overflow-x: auto;">
                    ${renderBcTableGraph(domain)}
                </div>
            </details>
            ` : ''}

            </div>  <!-- ⭐ padding 종료 -->
        </div>
    `;
}

// ⭐⭐⭐ 도메인 간 호출 관계 매트릭스 로딩
async function loadDomainMatrix() {
    const loading = document.getElementById('domainMatrixLoading');
    const container = document.getElementById('domainMatrixContainer');
    
    try {
        loading.style.display = 'block';
        container.style.display = 'none';
        
        console.log('도메인 매트릭스 요청 시작:', projectId);
        
        const res = await fetch(`/api/projects/${projectId}/rag/domain-matrix`);
        if (!res.ok) {
            throw new Error('매트릭스 생성 실패: ' + res.status);
        }
        
        domainMatrix = await res.json();
        console.log('도메인 매트릭스 데이터 수신:', domainMatrix);
        
        // 렌더링 먼저 수행
        renderDomainMatrix();
        
        // 렌더링 성공하면 로딩 숨기기
        loading.style.display = 'none';
        container.style.display = 'block';
        
    } catch (error) {
        console.error('도메인 매트릭스 로딩 실패:', error);
        loading.style.display = 'none';
        container.innerHTML = `
            <div style="color: #dc3545; padding: 20px; text-align: center;">
                ❌ 매트릭스 생성 실패: ${escapeHtml(error.message)}<br>
                <small>${escapeHtml(error.stack || '')}</small>
            </div>
        `;
        container.style.display = 'block';
    }
}

// ⭐⭐⭐ 도메인 간 호출 관계 매트릭스 렌더링
function renderDomainMatrix() {
    try {
        console.log('매트릭스 렌더링 시작:', domainMatrix);
        
        if (!domainMatrix || !domainMatrix.domainNames || domainMatrix.domainNames.length === 0) {
            document.getElementById('domainMatrixTable').innerHTML = 
                '<div style="color: #999; text-align: center; padding: 20px;">매트릭스 데이터가 없습니다</div>';
            return;
        }
        
        const domains = domainMatrix.domainNames;
        const interactions = domainMatrix.interactions || {};
        const stats = domainMatrix.statistics || {};
        
        console.log('도메인 수:', domains.length, '상호작용:', Object.keys(interactions).length);
        
        // 요약
        const totalInteractions = Object.values(interactions).reduce((sum, targets) => 
            sum + Object.values(targets).reduce((s, count) => s + count, 0), 0);
        const avgInternalCallRatio = Object.values(stats).length > 0 
            ? Object.values(stats).reduce((sum, s) => sum + (s.internalCallRatio || s.cohesion || 0), 0) / domains.length 
            : 0;
        
        // 요약 - ⭐ 내부/외부 분리
        const selfInteractions = Object.keys(interactions).reduce((sum, caller) => {
            return sum + (interactions[caller][caller] || 0);
        }, 0);
        const crossDomainInteractions = totalInteractions - selfInteractions;
        const totalCommonCalls = domainMatrix.totalCommonCalls || 0;
        
        document.getElementById('domainMatrixSummary').innerHTML = `
        <div class="summary-grid">
            <div class="summary-box" style="border-left-color: #667eea;">
                <div class="summary-box-value">${domains.length}</div>
                <div class="summary-box-label">도메인 수</div>
            </div>
            <div class="summary-box" style="border-left-color: #28a745;">
                <div class="summary-box-value">${selfInteractions}</div>
                <div class="summary-box-label">내부 호출 (대각선)</div>
            </div>
            <div class="summary-box" style="border-left-color: #ffc107;">
                <div class="summary-box-value">${crossDomainInteractions}</div>
                <div class="summary-box-label">도메인 간 호출</div>
            </div>
            <div class="summary-box" style="border-left-color: #6c757d;">
                <div class="summary-box-value">${totalCommonCalls}</div>
                <div class="summary-box-label">공통/유틸 호출</div>
            </div>
            <div class="summary-box" style="border-left-color: ${avgInternalCallRatio >= 0.5 ? '#28a745' : (avgInternalCallRatio >= 0.3 ? '#ffc107' : '#dc3545')};">
                <div class="summary-box-value">${(avgInternalCallRatio * 100).toFixed(0)}%</div>
                <div class="summary-box-label">평균 InternalCallRatio</div>
            </div>
        </div>
        <div style="margin-top: 15px; padding: 12px; background: #e7f3ff; border-left: 4px solid #0066cc; border-radius: 8px; font-size: 0.9em;">
            💡 <strong>분석 결과 (JVM 내부 Service→Service 메서드 호출 기반):</strong><br>
            • <strong>내부 호출 ${selfInteractions}회</strong> = 도메인 내부 Bean 간 메서드 호출 (응집도 근거)<br>
            • <strong>도메인 간 호출 ${crossDomainInteractions}회</strong> = 타 도메인 Service를 직접 호출 (결합도 근거)
            ${crossDomainInteractions < 20 ? '<br>→ ✅ <strong>도메인 경계 양호</strong> (외부 호출 결합 낮음)' : 
                crossDomainInteractions < 40 ? '<br>→ ⚠️ <strong>중간</strong> (일부 강결합 검토 필요)' : 
                '<br>→ 🚨 <strong>강결합 위험</strong> (도메인 경계 재설계 필요)'}<br>
            • <strong>공통/유틸 호출 ${totalCommonCalls}회</strong> = 노이즈 제거됨 (Comm*, Common*, Util*, Egov* 등은 별도 분류)<br>
            • 📊 <strong>DataCoupling</strong>(테이블 직접 접근) / 🔄 <strong>TxCoupling</strong>(분산 Tx 위험) 매트릭스는 아래 섹션 참조
        </div>
    `;
    
    // 매트릭스 테이블
    let tableHTML = `
        <div style="overflow-x: auto; max-width: 100%; border: 1px solid #dee2e6; border-radius: 8px;">
            <table style="width: max-content; min-width: 100%; border-collapse: collapse; font-size: 0.85em;">
                <thead>
                    <tr style="background: #f8f9fa;">
                        <th style="padding: 12px; border: 1px solid #dee2e6; text-align: left; position: sticky; left: 0; background: #f8f9fa; z-index: 10; min-width: 180px;">
                            구분<br><span style="font-weight: normal; color: #666; font-size: 0.9em;">Caller ↓ / Callee →</span>
                        </th>
    `;
    
    // 열 헤더 (Callee) - ⭐ 가로 텍스트로 변경
    domains.forEach(domain => {
        tableHTML += `<th style="padding: 12px 8px; border: 1px solid #dee2e6; text-align: center; min-width: 140px; background: #f8f9fa; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">
            ${escapeHtml(domain)}
        </th>`;
    });
    tableHTML += '</tr></thead><tbody>';
    
    // 행 (Caller)
    domains.forEach(caller => {
        tableHTML += `<tr>`;
        tableHTML += `<td style="padding: 12px; border: 1px solid #dee2e6; font-weight: 600; position: sticky; left: 0; background: white; z-index: 5; white-space: nowrap;">
            ${escapeHtml(caller)}
        </td>`;
        
        domains.forEach(callee => {
            const count = interactions[caller]?.[callee] || 0;
            const isSelf = (caller === callee);
            const callDetails = domainMatrix.callDetails?.[caller]?.[callee] || [];
            
            let bgColor = '#ffffff';
            let textColor = '#000';
            let displayText = count === 0 ? '0' : count;
            
            if (isSelf && count > 0) {
                bgColor = '#d4edda'; textColor = '#155724';
                displayText = `[${count}]`;
            } else if (count >= 5) {
                bgColor = '#f8d7da'; textColor = '#721c24';
            } else if (count > 0) {
                bgColor = '#fff3cd'; textColor = '#856404';
            }

            // 클릭 시 호출 메서드 상세 보기 (cross-domain + call count > 0)
            const hasDetail = !isSelf && callDetails.length > 0;
            const detailId = `detail-${caller.replace(/\s/g,'-')}-${callee.replace(/\s/g,'-')}`;
            const queryCount   = callDetails.filter(d => d.startsWith('[QUERY]')).length;
            const commandCount = callDetails.filter(d => d.startsWith('[COMMAND]')).length;
            const typeBadge = hasDetail
                ? `<br><span style="font-size:0.7em;">${queryCount>0?'Q:'+queryCount:''}${commandCount>0?(queryCount>0?' ':' ')+'C:'+commandCount:''}</span>`
                : '';
            
            tableHTML += `<td style="padding: 8px; border: 1px solid #dee2e6; text-align: center; background: ${bgColor}; color: ${textColor}; ${hasDetail ? 'cursor:pointer;' : ''}">`;
            if (hasDetail) {
                tableHTML += `<div onclick="toggleCallDetail('${detailId}')" title="클릭: 호출 상세 보기">
                    ${displayText}${typeBadge}
                </div>
                <div id="${detailId}" style="display:none; text-align:left; font-size:0.75em; max-height:180px; overflow-y:auto; background:#fffde7; border:1px solid #ffc107; border-radius:4px; padding:6px; margin-top:4px; white-space:nowrap;">
                    ${callDetails.map(d => `<div style="padding:1px 0; border-bottom:1px solid #f0e0a0;">${escapeHtml(d)}</div>`).join('')}
                </div>`;
            } else {
                tableHTML += displayText;
            }
            tableHTML += `</td>`;
        });
        
        tableHTML += '</tr>';
    });
    
    tableHTML += '</tbody></table></div>';
    
    tableHTML += `
        <div style="margin-top: 15px; padding: 12px; background: #f8f9fa; border-radius: 8px; font-size: 0.85em;">
            <strong>💡 해석 가이드:</strong><br>
            • <span style="background: #d4edda; padding: 2px 8px; border-radius: 4px; font-weight: 600;">[N]</span> 대각선 = 도메인 내부 호출 (높을수록 좋음 ✅)<br>
            • <span style="background: #fff3cd; padding: 2px 8px; border-radius: 4px;">1~4</span> = 정상 협력 &nbsp;
            • <span style="background: #f8d7da; padding: 2px 8px; border-radius: 4px; font-weight: 600;">5+</span> = 강결합 위험 🚨<br>
            • <strong>평균 InternalCallRatio ${(avgInternalCallRatio * 100).toFixed(0)}%</strong> = 내부호출 / (내부+외부) &nbsp;
            • 숫자 클릭 → 호출 메서드 상세 (Q:조회 / C:명령)
        </div>
    `;
    
    document.getElementById('domainMatrixTable').innerHTML = tableHTML;
    
    // 통계
    if (stats) {
        let statsHTML = `
        <div class="card-title">📊 도메인별 통계 및 위험 분석</div>
        <div style="margin-bottom: 20px; padding: 15px; background: #d4edda; border-left: 4px solid #28a745; border-radius: 8px; font-size: 0.9em;">
            ✅ <strong>측정 방식 (v2 - JVM 내부 호출 포함):</strong><br>
            • ✅ <strong>Spring Bean 간 직접 호출</strong> (@Autowired, @Resource, private final 주입 → 메서드 호출 횟수 집계)<br>
            • ✅ <strong>공통/유틸 호출 분리</strong> (Comm*, Common*, Util*, Egov* 등 → 별도 카테고리, 노이즈 제거)<br>
            • ✅ <strong>Interface→Impl 정규화</strong> (Service 인터페이스 → 실제 구현체 매핑)<br><br>
            
            ⚠️ <strong>아직 미포함:</strong><br>
            • 공유 DAO/Mapper를 통한 간접 결합 (→ CRUD Matrix 참고)<br>
            • 공유 테이블을 통한 데이터 결합 (→ 도메인 서비스 탭 참고)<br><br>
            
            💡 <strong>해석 팁:</strong><br>
            • <strong>내부 호출 높음</strong> = 도메인 내부 서비스 간 협력 활발 → 응집도 양호<br>
            • <strong>외부 호출 높음</strong> = 타 도메인 서비스 직접 의존 → 분리 시 API 인터페이스 필요<br>
            • <strong>공통 호출 높음</strong> = 공통 유틸 의존 → 분리해도 영향 없음 (라이브러리화 가능)
        </div>
        `;
        Object.values(stats).forEach(stat => {
            const icrValue = stat.internalCallRatio ?? stat.cohesion ?? 0;
            const icrPercent = (icrValue * 100).toFixed(0);
            const icrColor = icrValue >= 0.7 ? '#28a745' : (icrValue >= 0.5 ? '#ffc107' : '#dc3545');
            
            // ⭐ 허브 도메인 감지
            const isHub = stat.inboundCalls >= 5;
            const hubBadge = isHub ? '<span style="background: #dc3545; color: white; padding: 2px 8px; border-radius: 12px; font-size: 0.8em; margin-left: 8px;">🔥 허브 도메인</span>' : '';
            
            statsHTML += `
                <div class="rec-card" style="margin-bottom: 15px; ${isHub ? 'border: 2px solid #dc3545; box-shadow: 0 4px 12px rgba(220, 53, 69, 0.2);' : ''}">
                    <div class="rec-header">
                        <div class="rec-title">🏢 ${escapeHtml(stat.domainName)}${hubBadge}</div>
                    </div>
                    
                    <div style="display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin-bottom: 15px;">
                        <div style="text-align: center; padding: 10px; background: #f8f9fa; border-radius: 8px;">
                            <div style="font-size: 1.5em; font-weight: 700; color: #28a745;">${stat.selfInvocation}</div>
                            <div style="font-size: 0.8em; color: #666;">내부 호출</div>
                        </div>
                        <div style="text-align: center; padding: 10px; background: #f8f9fa; border-radius: 8px;">
                            <div style="font-size: 1.5em; font-weight: 700; color: #007bff;">${stat.outboundCalls}</div>
                            <div style="font-size: 0.8em; color: #666;">외부 호출</div>
                        </div>
                        <div style="text-align: center; padding: 10px; background: #f8f9fa; border-radius: 8px;">
                            <div style="font-size: 1.5em; font-weight: 700; color: #6c757d;">${stat.inboundCalls}</div>
                            <div style="font-size: 0.8em; color: #666;">수신 호출</div>
                        </div>
                        <div style="text-align: center; padding: 10px; background: #f8f9fa; border-radius: 8px;">
                            <div style="font-size: 1.5em; font-weight: 700; color: #17a2b8;">${stat.commonCalls || 0}</div>
                            <div style="font-size: 0.8em; color: #666;">공통 호출</div>
                        </div>
                        <div style="text-align: center; padding: 10px; background: #f8f9fa; border-radius: 8px;">
                            <div style="font-size: 1.5em; font-weight: 700; color: ${icrColor};">${icrPercent}%</div>
                            <div style="font-size: 0.8em; color: #666;">InternalCallRatio</div>
                        </div>
                    </div>
                    
                    ${stat.risks && stat.risks.length > 0 ? `
                    <div class="rec-section">
                        <div class="rec-section-title">🚨 위험 요소</div>
                        <ul style="margin: 0; padding-left: 20px; color: #dc3545;">
                            ${stat.risks.map(risk => `<li style="margin: 5px 0;">${escapeHtml(risk)}</li>`).join('')}
                        </ul>
                    </div>
                    ` : '<div style="color: #28a745; padding: 10px; background: #d4edda; border-radius: 8px;">✅ 위험 요소 없음 (양호)</div>'}
                    
                    ${isHub ? `
                    <div style="margin-top: 15px; padding: 12px; background: #fff3cd; border-left: 4px solid #ffc107; border-radius: 8px; font-size: 0.9em;">
                        <strong>🔥 허브 도메인 전략:</strong><br>
                        • 이벤트 기반(Async) 우선 전환 (동기 호출 → 메시지 발행)<br>
                        • API 계약(인터페이스) 강제<br>
                        • DB 직접 접근 금지 (서비스 경계 유지)<br>
                        • 알림/메시징 같은 크로스컷팅 서비스는 독립 서비스 유지 권장
                    </div>
                    ` : ''}
                </div>
            `;
        });
        
        document.getElementById('domainMatrixStatistics').innerHTML = statsHTML;
    }

    // ===== DataCoupling 매트릭스 =====
    const dataCoupling = domainMatrix.dataCoupling || {};
    renderDataCouplingMatrix(domains, dataCoupling);

    // ===== TxCoupling 위험 =====
    const txRisks = domainMatrix.txCouplingRisks || [];
    renderTxCouplingRisks(txRisks);

    console.log('매트릭스 렌더링 완료');
    
    } catch (error) {
        console.error('매트릭스 렌더링 에러:', error);
        document.getElementById('domainMatrixContainer').innerHTML = `
            <div style="color: #dc3545; padding: 20px; text-align: center;">
                ❌ 매트릭스 렌더링 실패: ${escapeHtml(error.message)}<br>
                <small>브라우저 콘솔을 확인하세요</small>
            </div>
        `;
    }
}

// ===== DataCoupling 매트릭스 렌더링 =====
function renderDataCouplingMatrix(domains, dataCoupling) {
    const container = document.getElementById('dataCouplingContainer');
    if (!container) return;

    const anyData = Object.keys(dataCoupling).length > 0;
    if (!anyData) {
        container.innerHTML = `<div style="color:#999; padding:16px; text-align:center;">DataCoupling 데이터 없음 (소유 테이블 매핑이 없거나 도메인 간 테이블 접근 없음)</div>`;
        return;
    }

    let html = `
    <div class="card-title">📊 DataCoupling Matrix <small style="font-weight:normal; color:#666;">(Domain A → Domain B 소유 테이블 접근 횟수 / R=1pt · CUD=3pt)</small></div>
    <div style="overflow-x:auto; border:1px solid #dee2e6; border-radius:8px; margin-bottom:16px;">
    <table style="width:max-content; min-width:100%; border-collapse:collapse; font-size:0.82em;">
    <thead><tr style="background:#f8f9fa;">
        <th style="padding:10px 14px; border:1px solid #dee2e6; text-align:left; position:sticky; left:0; background:#f8f9fa; z-index:10; min-width:180px;">
            Accessor ↓ / Owner →
        </th>`;

    domains.forEach(d => {
        html += `<th style="padding:10px 8px; border:1px solid #dee2e6; text-align:center; min-width:130px; white-space:nowrap;">${escapeHtml(d)}</th>`;
    });
    html += `</tr></thead><tbody>`;

    domains.forEach(caller => {
        html += `<tr><td style="padding:10px 14px; border:1px solid #dee2e6; font-weight:600; position:sticky; left:0; background:#fff; z-index:5; white-space:nowrap;">${escapeHtml(caller)}</td>`;
        domains.forEach(callee => {
            const entry = dataCoupling[caller]?.[callee];
            if (!entry || (entry.readCount === 0 && entry.cudCount === 0)) {
                const isSelf = caller === callee;
                html += `<td style="padding:10px; border:1px solid #dee2e6; text-align:center; background:${isSelf ? '#f0f0f0' : '#fff'}; color:#ccc;">${isSelf ? '—' : '0'}</td>`;
            } else {
                const weight = entry.totalWeight || 0;
                const hasCud = entry.cudCount > 0;
                const bg = hasCud ? (weight >= 10 ? '#f8d7da' : '#fff3cd') : '#e8f4f8';
                const color = hasCud ? (weight >= 10 ? '#721c24' : '#856404') : '#0c5460';
                const tables = (entry.accessedTables || []).join(', ');
                html += `<td style="padding:8px; border:1px solid #dee2e6; text-align:center; background:${bg}; color:${color}; cursor:help;" title="${escapeHtml(tables)}">
                    <div style="font-weight:700;">${weight}pt</div>
                    <div style="font-size:0.8em;">R:${entry.readCount} / CUD:${entry.cudCount}</div>
                </td>`;
            }
        });
        html += `</tr>`;
    });

    html += `</tbody></table></div>
    <div style="padding:10px 14px; background:#e7f3ff; border-left:4px solid #0066cc; border-radius:8px; font-size:0.83em;">
        💡 <strong>R(읽기)=1pt / C+U+D(쓰기)=3pt each</strong> · 셀 마우스오버 → 대상 테이블 목록<br>
        • <span style="background:#f8d7da; padding:1px 6px; border-radius:4px;">10pt+</span> = 강한 데이터 결합 🚨 &nbsp;
        • <span style="background:#fff3cd; padding:1px 6px; border-radius:4px;">CUD</span> = 쓰기 결합 (분리 시 분산 Tx 위험) &nbsp;
        • <span style="background:#e8f4f8; padding:1px 6px; border-radius:4px;">R</span> = 읽기 접근 (Read Model/API 분리 가능)
    </div>`;

    container.innerHTML = html;
}

// ===== TxCoupling 위험 렌더링 =====
function renderTxCouplingRisks(txRisks) {
    const container = document.getElementById('txCouplingContainer');
    if (!container) return;

    if (!txRisks || txRisks.length === 0) {
        container.innerHTML = `<div style="padding:12px; background:#d4edda; border-left:4px solid #28a745; border-radius:8px; font-size:0.9em;">✅ @Transactional 내 다중 도메인 CUD 감지 없음 (분산 트랜잭션 위험 낮음)</div>`;
        return;
    }

    let html = `<div class="card-title">🔄 TxCoupling 위험 <small style="font-weight:normal;color:#666;">(@Transactional 스코프에서 다중 도메인 테이블 CUD)</small></div>
    <div style="margin-bottom:12px; padding:10px 14px; background:#fff3cd; border-left:4px solid #ffc107; border-radius:8px; font-size:0.85em;">
        ⚠️ 아래 BC는 <strong>하나의 @Transactional 메서드 내에서</strong> 여러 도메인의 테이블에 C/U/D를 수행합니다.<br>
        마이크로서비스 분리 시 <strong>분산 트랜잭션(2PC·Saga 패턴)</strong>이 필요하게 됩니다.
    </div>`;

    txRisks.forEach(risk => {
        const isHigh = risk.riskLevel === 'HIGH';
        const borderColor = isHigh ? '#dc3545' : '#ffc107';
        const badgeColor = isHigh ? '#dc3545' : '#856404';
        html += `
        <div style="margin-bottom:14px; padding:14px; border:2px solid ${borderColor}; border-radius:10px; background:${isHigh ? '#fff5f5' : '#fffdf0'};">
            <div style="display:flex; align-items:center; gap:10px; margin-bottom:8px;">
                <span style="font-weight:700; font-size:1.05em;">${escapeHtml(risk.bcName)}</span>
                <span style="background:${badgeColor}; color:#fff; padding:2px 10px; border-radius:10px; font-size:0.8em;">${risk.riskLevel}</span>
                <span style="color:#888; font-size:0.85em;">도메인: ${escapeHtml(risk.domainName)}</span>
            </div>
            <div style="font-size:0.85em; color:#555; margin-bottom:8px;">🔧 <strong>Service:</strong> ${escapeHtml(risk.serviceName || '-')}</div>
            <div style="font-size:0.85em; margin-bottom:6px;">
                <strong>🌐 관여 도메인 (CUD 대상):</strong>
                ${(risk.involvedDomains || []).map(d => `<span style="background:#ffeeba; padding:2px 8px; border-radius:8px; margin:2px;">${escapeHtml(d)}</span>`).join('')}
            </div>
            <div style="font-size:0.82em; background:#f8f9fa; border-radius:6px; padding:8px;">
                ${Object.entries(risk.tablesByDomain || {}).map(([dom, tables]) =>
                    `<div><strong>${escapeHtml(dom)}:</strong> <code>${escapeHtml(tables)}</code></div>`
                ).join('')}
            </div>
            <div style="margin-top:10px; padding:8px; background:#e7f3ff; border-radius:6px; font-size:0.82em; color:#0066cc;">
                💡 <strong>해결 전략:</strong> Saga 패턴(Choreography) 또는 Outbox 패턴으로 분산 Tx 처리, 또는 해당 BC를 단일 도메인으로 통합 후 분리
            </div>
        </div>`;
    });

    container.innerHTML = html;
}

// ===== BC ↔ 테이블 연결도 SVG 렌더링 =====
function renderBcTableGraph(domain) {
    const links    = domain.bcTableLinks || [];
    const ownedSet = new Set(domain.ownedTables || []);

    // ① BC 목록: domain.bcNames 기준 (테이블 없는 BC도 포함)
    const allBcNames = domain.bcNames || [];
    // links에만 있는 BC가 혹시 있으면 추가 (방어)
    const extraBcs = links.map(l => l.bcName).filter(b => !allBcNames.includes(b));
    const bcs = [...allBcNames, ...new Set(extraBcs)];

    // ② Table 목록: links 기준 (소유 테이블 우선 정렬, 그 다음 나머지)
    const tablesFromLinks = [...new Set(links.map(l => l.tableName))];
    const owned    = tablesFromLinks.filter(t => ownedSet.has(t)).sort();
    const accessed = tablesFromLinks.filter(t => !ownedSet.has(t)).sort();
    const tables   = [...owned, ...accessed];

    if (bcs.length === 0) return '<div style="color:#999;padding:12px;">BC 데이터 없음</div>';

    // ─── 레이아웃 상수 ───
    const ROW_H  = 36;
    const NODE_H = 28;
    const BC_W   = 210;
    const TBL_W  = 210;
    const PAD_V  = 28;   // 상단(레이블) 여백
    const LEG_H  = 22;   // 하단 범례 여백
    const GAP_H  = tables.length > 0 ? 150 : 0;  // 테이블이 없으면 간격 0

    const bcColX  = 10;
    const tblColX = bcColX + BC_W + GAP_H;
    const svgW    = tables.length > 0 ? tblColX + TBL_W + 10 : bcColX + BC_W + 10;

    const bcCount  = bcs.length;
    const tblCount = tables.length;
    const svgH     = Math.max(bcCount, tblCount || 1) * ROW_H + PAD_V + LEG_H + 10;

    // Y 좌표 (작은 쪽은 세로 중앙 정렬)
    const bcTotalH  = bcCount  * ROW_H;
    const tblTotalH = tblCount * ROW_H;
    const bcOffY    = PAD_V + Math.max(0, (svgH - PAD_V - LEG_H - bcTotalH)  / 2);
    const tblOffY   = PAD_V + Math.max(0, (svgH - PAD_V - LEG_H - tblTotalH) / 2);

    const bcY  = i => bcOffY  + i * ROW_H;
    const tblY = i => tblOffY + i * ROW_H;

    // 인덱스 맵
    const bcIdx  = Object.fromEntries(bcs.map((b, i)   => [b, i]));
    const tblIdx = Object.fromEntries(tables.map((t, i) => [t, i]));

    // ─── 연결선 ───
    // BC별 연결선 개수 집계 (겹침 방지를 위한 Y 오프셋 계산용)
    const bcLinkCount = {};
    links.forEach(l => { bcLinkCount[l.bcName] = (bcLinkCount[l.bcName] || 0) + 1; });

    let edges = '';
    // BC별로 선을 묶어서 부채꼴로 퍼지게 (Y 오프셋 적용)
    const bcLinkSeen = {};
    links.forEach(link => {
        const bcI  = bcIdx[link.bcName];
        const tblI = tblIdx[link.tableName];
        if (bcI === undefined || tblI === undefined) return;

        const total = bcLinkCount[link.bcName] || 1;
        bcLinkSeen[link.bcName] = (bcLinkSeen[link.bcName] || 0) + 1;
        const seq = bcLinkSeen[link.bcName] - 1;
        // 선이 여러 개면 BC 노드 우측 엣지를 위아래로 분산
        const spread = Math.min(NODE_H * 0.4, (NODE_H / (total + 1)) * (seq + 1) - NODE_H / 2);

        const hasCUD = link.ops.some(o => o !== 'R');
        const stroke = hasCUD ? (link.owned ? '#e74c3c' : '#f39c12') : '#3498db';
        const dash   = hasCUD ? 'none' : '5,3';
        const sw     = hasCUD ? 2.0 : 1.5;

        const x1  = bcColX + BC_W;
        const y1  = bcY(bcI) + NODE_H / 2 + spread;
        const x2  = tblColX;
        const y2  = tblY(tblI) + NODE_H / 2;
        const cx1 = x1 + GAP_H * 0.4;
        const cx2 = x2 - GAP_H * 0.4;

        edges += `<path d="M${x1},${y1} C${cx1},${y1} ${cx2},${y2} ${x2},${y2}"
            fill="none" stroke="${stroke}" stroke-width="${sw}" stroke-dasharray="${dash}" opacity="0.8">
            <title>${link.bcName} → ${link.tableName} [${link.ops.join('')}]</title>
        </path>`;
    });

    // ─── BC 노드 ───
    let bcNodes = '';
    bcs.forEach((bc, i) => {
        const y = bcY(i);
        const hasLinks = links.some(l => l.bcName === bc);
        const fill   = hasLinks ? '#ebf0ff' : '#f0f0f0';
        const stroke = hasLinks ? '#667eea' : '#adb5bd';
        const textColor = hasLinks ? '#2c3e50' : '#999';
        // BC명에서 " BC" 접미사 제거해서 표시
        const label = bc.replace(/ BC$/, '');
        // 너비 초과 시 말줄임 (SVG는 clip-path 대신 text 잘라서 표기)
        const maxLen = 22;
        const displayLabel = label.length > maxLen ? label.slice(0, maxLen) + '…' : label;
        bcNodes += `
        <g>
            <rect x="${bcColX}" y="${y}" width="${BC_W}" height="${NODE_H}"
                rx="6" fill="${fill}" stroke="${stroke}" stroke-width="1.5"/>
            <text x="${bcColX + BC_W/2}" y="${y + NODE_H*0.66}"
                font-size="11" fill="${textColor}" text-anchor="middle"
                font-family="system-ui,sans-serif">
                <title>${escapeHtml(bc)}</title>
                ${escapeHtml(displayLabel)}
            </text>
        </g>`;
    });

    // ─── Table 노드 ───
    let tblNodes = '';
    tables.forEach((tbl, i) => {
        const y       = tblY(i);
        const isOwned = ownedSet.has(tbl);
        const fill    = isOwned ? '#fff0f0' : '#f8f9fa';
        const stroke  = isOwned ? '#e74c3c' : '#adb5bd';
        const dot     = isOwned ? '● ' : '○ ';
        const dotCol  = isOwned ? '#e74c3c' : '#adb5bd';
        const maxLen  = 22;
        const displayTbl = tbl.length > maxLen ? tbl.slice(0, maxLen) + '…' : tbl;
        tblNodes += `
        <g>
            <rect x="${tblColX}" y="${y}" width="${TBL_W}" height="${NODE_H}"
                rx="5" fill="${fill}" stroke="${stroke}" stroke-width="1.5"/>
            <text x="${tblColX + 6}" y="${y + NODE_H*0.66}"
                font-size="10.5" fill="${dotCol}" text-anchor="start"
                font-family="monospace,system-ui">${dot}</text>
            <text x="${tblColX + 18}" y="${y + NODE_H*0.66}"
                font-size="10.5" fill="#333" text-anchor="start"
                font-family="monospace,system-ui">
                <title>${escapeHtml(tbl)}</title>
                ${escapeHtml(displayTbl)}
            </text>
        </g>`;
    });

    // ─── 헤더 레이블 ───
    const headerBC  = `<text x="${bcColX + BC_W/2}"  y="18" font-size="11" fill="#667eea" font-weight="700" text-anchor="middle" font-family="system-ui">Bounded Context (${bcs.length})</text>`;
    const headerTBL = tables.length > 0
        ? `<text x="${tblColX + TBL_W/2}" y="18" font-size="11" fill="#555"    font-weight="700" text-anchor="middle" font-family="system-ui">DB Table (${tables.length})</text>`
        : '';

    // ─── 범례 ───
    const legendY = svgH - LEG_H + 6;
    const legend = tables.length > 0 ? `
        <g transform="translate(${bcColX}, ${legendY})">
            <line x1="0" y1="5" x2="20" y2="5" stroke="#e74c3c" stroke-width="2"/>
            <text x="24" y="9" font-size="10" fill="#555" font-family="system-ui">소유 CUD</text>
            <line x1="82" y1="5" x2="102" y2="5" stroke="#f39c12" stroke-width="2"/>
            <text x="106" y="9" font-size="10" fill="#555" font-family="system-ui">외부 CUD</text>
            <line x1="170" y1="5" x2="190" y2="5" stroke="#3498db" stroke-width="1.6" stroke-dasharray="5,3"/>
            <text x="194" y="9" font-size="10" fill="#555" font-family="system-ui">READ only</text>
            <circle cx="265" cy="5" r="4" fill="none" stroke="#adb5bd" stroke-width="1.5"/>
            <text x="272" y="9" font-size="10" fill="#999" font-family="system-ui">접근 테이블</text>
            <circle cx="345" cy="5" r="4" fill="#e74c3c" stroke="#e74c3c"/>
            <text x="352" y="9" font-size="10" fill="#e74c3c" font-family="system-ui">소유 테이블</text>
        </g>` : '';

    const noTableNote = tables.length === 0
        ? `<text x="${bcColX + BC_W/2}" y="${svgH - LEG_H + 10}" font-size="10.5" fill="#adb5bd" text-anchor="middle" font-family="system-ui">DB 테이블 접근 데이터 없음 (Mapper 미검출 또는 Read-only)</text>`
        : '';

    const noLinkBcCount = bcs.filter(b => !links.some(l => l.bcName === b)).length;
    return `
    <svg xmlns="http://www.w3.org/2000/svg" width="${svgW}" height="${svgH}"
         style="min-width:${Math.min(svgW, 620)}px; width:100%; max-width:${svgW}px;
                display:block; background:#fafafa; border-radius:10px; border:1px solid #dee2e6; overflow:visible;">
        ${headerBC}${headerTBL}
        ${edges}
        ${bcNodes}
        ${tblNodes}
        ${legend}
        ${noTableNote}
    </svg>
    <div style="font-size:0.77em; color:#666; padding:6px 8px; background:#f8f9fa; border-radius:0 0 10px 10px; border:1px solid #dee2e6; border-top:none; line-height:1.6;">
        <strong>그래프 해석:</strong>
        이 그래프는 각 BC가 <em>직접 접근(SELECT/INSERT/UPDATE/DELETE)</em>하는 DB 테이블을 보여줍니다.<br>
        <span style="color:#e74c3c;">● 빨간 테이블</span> = 이 도메인이 <strong>소유</strong>하는 테이블 (CUD 점수 최고, Primary Owner)<br>
        <span style="color:#adb5bd;">○ 회색 테이블</span> = 다른 도메인이 소유하지만 이 BC가 <strong>접근</strong>하는 테이블 (데이터 결합 가능성)<br>
        ${noLinkBcCount > 0 ? `<span style="color:#aaa;">회색 BC 노드</span> = Mapper XML 미검출 또는 다른 서비스를 통해 간접 접근 (직접 DB 호출 없음)<br>` : ''}
        선 위에 마우스오버 → 수행 연산 상세 표시
    </div>`;
}

// ===== 호출 상세 토글 =====
function toggleCallDetail(id) {
    const el = document.getElementById(id);
    if (el) el.style.display = el.style.display === 'none' ? 'block' : 'none';
}

// ⭐⭐⭐ BC 캐시 삭제 & 재분석
async function deleteBcCache() {
    if (!confirm('BC 분석 캐시를 삭제하고 재분석하시겠습니까?\n\n⏱️ 재분석 시간: 약 20분 소요')) {
        return;
    }
    
    try {
        // 1. 캐시 삭제
        const deleteRes = await fetch(`/api/projects/${projectId}/rag/cache/bc-analysis`, {
            method: 'DELETE'
        });
        
        if (!deleteRes.ok) {
            throw new Error('캐시 삭제 실패: ' + deleteRes.status);
        }
        
        const deleteData = await deleteRes.json();
        console.log('캐시 삭제:', deleteData);
        
        // 2. BC 재분석 자동 실행
        alert('✅ 캐시 삭제 완료!\n\n지금 BC 재분석을 시작합니다. (약 20분 소요)');
        
        // 캐시 초기화
        bcGroups = null;
        domains = null;
        domainMatrix = null;
        
        // BC 그룹 탭으로 이동 & 재분석
        switchTab('bc-groups');
        
    } catch (error) {
        console.error('캐시 삭제 실패:', error);
        alert('❌ 캐시 삭제 실패: ' + error.message);
    }
}
