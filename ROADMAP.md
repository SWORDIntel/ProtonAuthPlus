# ProtonAuthPlus Improvement Roadmap

## Phases
- [x] Phase 1: Security - Pre-wipe verification & typed YubiKey exceptions in `business/entries`
- [x] Phase 2: Performance - Single-session batch code calculation (`INS_CALCULATE_ALL`) in `business/entry_codes`
- [x] Phase 3: Hardening - Eliminate unencrypted exports & add typed error dialogs in `features/exports` & `features/backups`
- [x] Phase 4: UI/UX - Hardware token badge chip & indicators in `features/home/master`
- [x] Phase 5: UX - Discovery loading states & typed error snackbars in `features/home/manual` & `features/home/scan`
- [x] Phase 6: Slot Hygiene - Hardware credential deletion on entry removal in `business/entries`
- [x] Phase 7: Data Privacy - Room encrypted hardware metadata storage in `business/shared` & `business/entries`
- [x] Phase 8: Build & Manifest - USB Host declaration, AGP 8.9 localeFilters, and F-Droid release verification
- [x] Phase 9: Verification - Centralized multi-module test suite pass
- [x] Phase 10: Packaging - F-Droid prod release verification and distribution build
