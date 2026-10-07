; Kaldırırken "Windows açılışında başlat" girdisini de sil (uygulama bunu HKCU\...\Run altına yazar).
!macro NSIS_HOOK_POSTUNINSTALL
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "Sticky"
!macroend
