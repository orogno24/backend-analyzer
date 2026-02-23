let selectedFile = null;
let currentProjectId = null;

// 초기화
document.addEventListener('DOMContentLoaded', () => {
    loadProjects();
    setupEventListeners();
});

// 이벤트 리스너 설정
function setupEventListeners() {
    const dropZone = document.getElementById('dropZone');
    const fileInput = document.getElementById('fileInput');
    const uploadForm = document.getElementById('uploadForm');

    // 드래그 앤 드롭
    dropZone.addEventListener('click', () => fileInput.click());
    
    dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropZone.classList.add('drag-over');
    });

    dropZone.addEventListener('dragleave', () => {
        dropZone.classList.remove('drag-over');
    });

    dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropZone.classList.remove('drag-over');
        const files = e.dataTransfer.files;
        if (files.length > 0) {
            handleFileSelect(files[0]);
        }
    });

    fileInput.addEventListener('change', (e) => {
        if (e.target.files.length > 0) {
            handleFileSelect(e.target.files[0]);
        }
    });

    uploadForm.addEventListener('submit', handleUpload);
}

// 파일 선택 처리
function handleFileSelect(file) {
    if (file.name.endsWith('.zip')) {
        selectedFile = file;
        document.getElementById('fileName').textContent = `선택된 파일: ${file.name}`;
    } else {
        alert('zip 파일만 업로드 가능합니다');
    }
}

// 프로젝트 업로드
async function handleUpload(e) {
    e.preventDefault();

    if (!selectedFile) {
        alert('파일을 선택해주세요');
        return;
    }

    const name = document.getElementById('projectName').value;
    const description = document.getElementById('projectDescription').value;

    const formData = new FormData();
    formData.append('file', selectedFile);
    formData.append('name', name);
    formData.append('description', description);

    const uploadBtn = document.getElementById('uploadBtn');
    uploadBtn.disabled = true;
    uploadBtn.innerHTML = '<span class="loading"></span> 업로드 중...';

    try {
        const response = await fetch('/api/projects/upload', {
            method: 'POST',
            body: formData
        });

        if (!response.ok) throw new Error('업로드 실패');

        const project = await response.json();
        alert('프로젝트가 업로드되었습니다');

        // 자동으로 분석 시작
        await analyzeProject(project.id);

        // 폼 초기화
        document.getElementById('uploadForm').reset();
        document.getElementById('fileName').textContent = '';
        selectedFile = null;

        // 목록 새로고침
        loadProjects();

    } catch (error) {
        alert('업로드 중 오류가 발생했습니다: ' + error.message);
    } finally {
        uploadBtn.disabled = false;
        uploadBtn.textContent = '업로드 및 분석 시작';
    }
}

// 프로젝트 분석
async function analyzeProject(projectId) {
    try {
        const response = await fetch(`/api/analysis/${projectId}/analyze`, {
            method: 'POST'
        });

        if (!response.ok) throw new Error('분석 실패');

        const result = await response.json();
        currentProjectId = projectId;

        // 결과 표시
        document.getElementById('resultCard').classList.remove('hidden');
        document.getElementById('resultBox').textContent = 
            JSON.stringify(JSON.parse(result.structureAnalysis), null, 2);

        return result;

    } catch (error) {
        alert('분석 중 오류가 발생했습니다: ' + error.message);
        throw error;
    }
}

// 프로젝트 목록 로드
async function loadProjects() {
    try {
        const response = await fetch('/api/projects');
        const projects = await response.json();

        const projectList = document.getElementById('projectList');

        if (projects.length === 0) {
            projectList.innerHTML = '<p style="text-align: center; color: #999;">프로젝트를 업로드해주세요</p>';
            return;
        }

        projectList.innerHTML = projects.map(project => `
            <div class="project-item">
                <div class="project-info">
                    <h3>${project.name}</h3>
                    <p>${project.description || '설명 없음'}</p>
                    <p style="font-size: 0.8em; color: #999;">${new Date(project.createdAt).toLocaleString()}</p>
                </div>
                <div>
                    <span class="status ${project.status.toLowerCase()}">${getStatusText(project.status)}</span>
                    ${project.status === 'COMPLETED' ? 
                        `<button onclick="viewResult(${project.id})" style="margin-left: 10px;">결과 보기</button>` : 
                        ''}
                    <button onclick="deleteProject(${project.id})" style="margin-left: 10px; background: #ff6b6b;">삭제</button>
                </div>
            </div>
        `).join('');

    } catch (error) {
        console.error('프로젝트 목록 로드 실패:', error);
    }
}

// 상태 텍스트 변환
function getStatusText(status) {
    const statusMap = {
        'UPLOADED': '대기중',
        'ANALYZING': '분석중',
        'COMPLETED': '완료',
        'FAILED': '실패'
    };
    return statusMap[status] || status;
}

// 분석 결과 보기
async function viewResult(projectId) {
    currentProjectId = projectId;
    
    try {
        const response = await fetch(`/api/analysis/${projectId}/result`);
        const result = await response.json();

        document.getElementById('resultCard').classList.remove('hidden');
        document.getElementById('resultBox').textContent = 
            JSON.stringify(JSON.parse(result.structureAnalysis), null, 2);

        // 스크롤
        document.getElementById('resultCard').scrollIntoView({ behavior: 'smooth' });

    } catch (error) {
        alert('결과를 불러오는 중 오류가 발생했습니다: ' + error.message);
    }
}

// 구조 분석 표시
async function showStructureAnalysis() {
    if (!currentProjectId) {
        alert('프로젝트를 먼저 선택해주세요');
        return;
    }

    try {
        const response = await fetch(`/api/analysis/${currentProjectId}/result`);
        const result = await response.json();

        document.getElementById('resultBox').textContent = 
            JSON.stringify(JSON.parse(result.structureAnalysis), null, 2);

    } catch (error) {
        alert('결과를 불러오는 중 오류가 발생했습니다: ' + error.message);
    }
}

// AI 분석 요청
async function requestAiAnalysis(analysisType) {
    if (!currentProjectId) {
        alert('프로젝트를 먼저 선택해주세요');
        return;
    }

    const resultBox = document.getElementById('resultBox');
    resultBox.textContent = '🤖 AI 분석 중...\n\nOllama가 분석 결과를 생성하고 있습니다.\n잠시만 기다려주세요...';

    try {
        const response = await fetch(`/api/analysis/${currentProjectId}/ai-analyze?analysisType=${analysisType}`, {
            method: 'POST'
        });

        if (!response.ok) throw new Error('AI 분석 실패');

        const aiResult = await response.text();
        resultBox.textContent = `=== ${analysisType} 결과 ===\n\n${aiResult}`;

    } catch (error) {
        resultBox.textContent = `AI 분석 중 오류가 발생했습니다:\n\n${error.message}\n\n` +
            `Ollama가 실행 중인지 확인해주세요.\n` +
            `터미널에서 "ollama serve" 명령으로 Ollama를 시작할 수 있습니다.`;
    }
}

// 프로젝트 삭제
async function deleteProject(projectId) {
    if (!confirm('정말 삭제하시겠습니까?')) return;

    try {
        const response = await fetch(`/api/projects/${projectId}`, {
            method: 'DELETE'
        });

        if (!response.ok) throw new Error('삭제 실패');

        alert('프로젝트가 삭제되었습니다');
        loadProjects();

        if (currentProjectId === projectId) {
            document.getElementById('resultCard').classList.add('hidden');
            currentProjectId = null;
        }

    } catch (error) {
        alert('삭제 중 오류가 발생했습니다: ' + error.message);
    }
}
