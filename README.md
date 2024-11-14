# 목표: 모바일 알림 사용패턴 파악

<br/>해당 애플리케이션을 활성화하면, 
<br/>1) 사용자의 스마트폰 센서 값과 앱 사용 로그를 실시간으로 저장
<br/>2) 1시간마다 구글 Firebase 데이터베이스로 전송

<br/>
<br/> 단 하나의 화면 UI만 제공하며, 사용자에게 요구하는 인터랙션은 없음
<br/> <img src= https://github.com/user-attachments/assets/53d265bd-1c1d-45df-ab9a-6306a8aeb34c width="300" height="500"/>
<br/>
<br/> 실험 수집 데이터
<br/> <img src=https://github.com/user-attachments/assets/1561d09d-8238-49f8-8462-323c5831ea88 width="500" height="300"/>
<br/>
<br/> 상단바를 내릴 때마다 평균 1~3개 앱 알림 누적 > 최대 14개까지 알림이 쌓이는 경우가 발생
<br/>누적된 알림이 많을 수록,
<br/>1) 사용자는 알림의 우선순위를 지정하는데 어려움 
<br/>2) 나중에 처리하려는 알림이 간과되거나 놓침
<br/> <img src=https://github.com/user-attachments/assets/fec3aed0-2355-49ba-938a-490de1a86379 width="500" height="300"/>
<br/>
<br/> 사용자마다 클릭 가능성이 높은 opportune moment를 다르게 설정한다면 수용률이 높아 질 것
<br/> <img src=https://github.com/user-attachments/assets/692e6e4d-f52e-45b1-b545-1abdd6e8ce50 width="500" height="300"/>
