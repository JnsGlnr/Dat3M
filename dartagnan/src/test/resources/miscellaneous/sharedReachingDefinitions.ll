; ModuleID = 'benchmarks/miscellaneous/sharedReachingDefinitions.c'
source_filename = "benchmarks/miscellaneous/sharedReachingDefinitions.c"
target datalayout = "e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128"
target triple = "x86_64-pc-linux-gnu"

@__func__.main = private unnamed_addr constant [5 x i8] c"main\00", align 1, !dbg !0
@.str = private unnamed_addr constant [28 x i8] c"sharedReachingDefinitions.c\00", align 1, !dbg !8
@.str.1 = private unnamed_addr constant [20 x i8] c"r2 <= N*(N*(N+1))/2\00", align 1, !dbg !13

; Function Attrs: noinline nounwind uwtable
define dso_local i32 @main() #0 !dbg !28 {
  %1 = alloca i32, align 4
  %2 = alloca i32, align 4
  %3 = alloca i32, align 4
  %4 = alloca i32, align 4
  %5 = alloca i32, align 4
  store i32 0, ptr %1, align 4
    #dbg_declare(ptr %2, !33, !DIExpression(), !34)
  store i32 0, ptr %2, align 4, !dbg !34
    #dbg_declare(ptr %3, !35, !DIExpression(), !37)
  store i32 0, ptr %3, align 4, !dbg !37
  br label %6, !dbg !38

6:                                                ; preds = %16, %0
  %7 = load i32, ptr %3, align 4, !dbg !39
  %8 = icmp slt i32 %7, 101, !dbg !41
  br i1 %8, label %9, label %19, !dbg !42

9:                                                ; preds = %6
  %10 = call zeroext i1 @__VERIFIER_nondet_bool(), !dbg !43
  br i1 %10, label %11, label %12, !dbg !46

11:                                               ; preds = %9
  br label %19, !dbg !47

12:                                               ; preds = %9
  %13 = load i32, ptr %3, align 4, !dbg !49
  %14 = load i32, ptr %2, align 4, !dbg !50
  %15 = add nsw i32 %14, %13, !dbg !50
  store i32 %15, ptr %2, align 4, !dbg !50
  br label %16, !dbg !51

16:                                               ; preds = %12
  %17 = load i32, ptr %3, align 4, !dbg !52
  %18 = add nsw i32 %17, 1, !dbg !52
  store i32 %18, ptr %3, align 4, !dbg !52
  br label %6, !dbg !53, !llvm.loop !54

19:                                               ; preds = %11, %6
    #dbg_declare(ptr %4, !57, !DIExpression(), !58)
  store i32 0, ptr %4, align 4, !dbg !58
    #dbg_declare(ptr %5, !59, !DIExpression(), !61)
  store i32 0, ptr %5, align 4, !dbg !61
  br label %20, !dbg !62

20:                                               ; preds = %30, %19
  %21 = load i32, ptr %5, align 4, !dbg !63
  %22 = icmp slt i32 %21, 100, !dbg !65
  br i1 %22, label %23, label %33, !dbg !66

23:                                               ; preds = %20
  %24 = call zeroext i1 @__VERIFIER_nondet_bool(), !dbg !67
  br i1 %24, label %25, label %26, !dbg !70

25:                                               ; preds = %23
  br label %33, !dbg !71

26:                                               ; preds = %23
  %27 = load i32, ptr %2, align 4, !dbg !73
  %28 = load i32, ptr %4, align 4, !dbg !74
  %29 = add nsw i32 %28, %27, !dbg !74
  store i32 %29, ptr %4, align 4, !dbg !74
  br label %30, !dbg !75

30:                                               ; preds = %26
  %31 = load i32, ptr %5, align 4, !dbg !76
  %32 = add nsw i32 %31, 1, !dbg !76
  store i32 %32, ptr %5, align 4, !dbg !76
  br label %20, !dbg !77, !llvm.loop !78

33:                                               ; preds = %25, %20
  %34 = load i32, ptr %4, align 4, !dbg !80
  %35 = icmp sle i32 %34, 505000, !dbg !80
  %36 = xor i1 %35, true, !dbg !80
  %37 = zext i1 %36 to i32, !dbg !80
  %38 = sext i32 %37 to i64, !dbg !80
  %39 = icmp ne i64 %38, 0, !dbg !80
  br i1 %39, label %40, label %42, !dbg !80

40:                                               ; preds = %33
  call void @__assert_rtn(ptr noundef @__func__.main, ptr noundef @.str, i32 noundef 29, ptr noundef @.str.1) #3, !dbg !80
  unreachable, !dbg !80

41:                                               ; No predecessors!
  br label %43, !dbg !80

42:                                               ; preds = %33
  br label %43, !dbg !80

43:                                               ; preds = %42, %41
  %44 = load i32, ptr %1, align 4, !dbg !81
  ret i32 %44, !dbg !81
}

declare zeroext i1 @__VERIFIER_nondet_bool() #1

; Function Attrs: cold noreturn
declare void @__assert_rtn(ptr noundef, ptr noundef, i32 noundef, ptr noundef) #2

attributes #0 = { noinline nounwind uwtable "frame-pointer"="all" "min-legal-vector-width"="0" "no-trapping-math"="true" "stack-protector-buffer-size"="8" "target-cpu"="x86-64" "target-features"="+cmov,+cx8,+fxsr,+mmx,+sse,+sse2,+x87" "tune-cpu"="generic" }
attributes #1 = { "frame-pointer"="all" "no-trapping-math"="true" "stack-protector-buffer-size"="8" "target-cpu"="x86-64" "target-features"="+cmov,+cx8,+fxsr,+mmx,+sse,+sse2,+x87" "tune-cpu"="generic" }
attributes #2 = { cold noreturn "disable-tail-calls"="true" "frame-pointer"="all" "no-trapping-math"="true" "stack-protector-buffer-size"="8" "target-cpu"="x86-64" "target-features"="+cmov,+cx8,+fxsr,+mmx,+sse,+sse2,+x87" "tune-cpu"="generic" }
attributes #3 = { cold noreturn }

!llvm.dbg.cu = !{!18}
!llvm.module.flags = !{!20, !21, !22, !23, !24, !25, !26}
!llvm.ident = !{!27}

!0 = !DIGlobalVariableExpression(var: !1, expr: !DIExpression())
!1 = distinct !DIGlobalVariable(scope: null, file: !2, line: 29, type: !3, isLocal: true, isDefinition: true)
!2 = !DIFile(filename: "benchmarks/miscellaneous/sharedReachingDefinitions.c", directory: "/Users/r/git/dat3m", checksumkind: CSK_MD5, checksum: "9929755f9a536fc176f7bbb8263c3355")
!3 = !DICompositeType(tag: DW_TAG_array_type, baseType: !4, size: 40, elements: !6)
!4 = !DIDerivedType(tag: DW_TAG_const_type, baseType: !5)
!5 = !DIBasicType(name: "char", size: 8, encoding: DW_ATE_signed_char)
!6 = !{!7}
!7 = !DISubrange(count: 5)
!8 = !DIGlobalVariableExpression(var: !9, expr: !DIExpression())
!9 = distinct !DIGlobalVariable(scope: null, file: !2, line: 29, type: !10, isLocal: true, isDefinition: true)
!10 = !DICompositeType(tag: DW_TAG_array_type, baseType: !5, size: 224, elements: !11)
!11 = !{!12}
!12 = !DISubrange(count: 28)
!13 = !DIGlobalVariableExpression(var: !14, expr: !DIExpression())
!14 = distinct !DIGlobalVariable(scope: null, file: !2, line: 29, type: !15, isLocal: true, isDefinition: true)
!15 = !DICompositeType(tag: DW_TAG_array_type, baseType: !5, size: 160, elements: !16)
!16 = !{!17}
!17 = !DISubrange(count: 20)
!18 = distinct !DICompileUnit(language: DW_LANG_C11, file: !2, producer: "Homebrew clang version 19.1.7", isOptimized: false, runtimeVersion: 0, emissionKind: FullDebug, globals: !19, splitDebugInlining: false, nameTableKind: None)
!19 = !{!0, !8, !13}
!20 = !{i32 7, !"Dwarf Version", i32 5}
!21 = !{i32 2, !"Debug Info Version", i32 3}
!22 = !{i32 1, !"wchar_size", i32 4}
!23 = !{i32 8, !"PIC Level", i32 2}
!24 = !{i32 7, !"PIE Level", i32 2}
!25 = !{i32 7, !"uwtable", i32 2}
!26 = !{i32 7, !"frame-pointer", i32 2}
!27 = !{!"Homebrew clang version 19.1.7"}
!28 = distinct !DISubprogram(name: "main", scope: !2, file: !2, line: 10, type: !29, scopeLine: 11, spFlags: DISPFlagDefinition, unit: !18, retainedNodes: !32)
!29 = !DISubroutineType(types: !30)
!30 = !{!31}
!31 = !DIBasicType(name: "int", size: 32, encoding: DW_ATE_signed)
!32 = !{}
!33 = !DILocalVariable(name: "r1", scope: !28, file: !2, line: 12, type: !31)
!34 = !DILocation(line: 12, column: 9, scope: !28)
!35 = !DILocalVariable(name: "i", scope: !36, file: !2, line: 13, type: !31)
!36 = distinct !DILexicalBlock(scope: !28, file: !2, line: 13, column: 5)
!37 = !DILocation(line: 13, column: 14, scope: !36)
!38 = !DILocation(line: 13, column: 10, scope: !36)
!39 = !DILocation(line: 13, column: 21, scope: !40)
!40 = distinct !DILexicalBlock(scope: !36, file: !2, line: 13, column: 5)
!41 = !DILocation(line: 13, column: 23, scope: !40)
!42 = !DILocation(line: 13, column: 5, scope: !36)
!43 = !DILocation(line: 14, column: 13, scope: !44)
!44 = distinct !DILexicalBlock(scope: !45, file: !2, line: 14, column: 13)
!45 = distinct !DILexicalBlock(scope: !40, file: !2, line: 13, column: 35)
!46 = !DILocation(line: 14, column: 13, scope: !45)
!47 = !DILocation(line: 14, column: 41, scope: !48)
!48 = distinct !DILexicalBlock(scope: !44, file: !2, line: 14, column: 39)
!49 = !DILocation(line: 15, column: 15, scope: !45)
!50 = !DILocation(line: 15, column: 12, scope: !45)
!51 = !DILocation(line: 16, column: 5, scope: !45)
!52 = !DILocation(line: 13, column: 31, scope: !40)
!53 = !DILocation(line: 13, column: 5, scope: !40)
!54 = distinct !{!54, !42, !55, !56}
!55 = !DILocation(line: 16, column: 5, scope: !36)
!56 = !{!"llvm.loop.mustprogress"}
!57 = !DILocalVariable(name: "r2", scope: !28, file: !2, line: 22, type: !31)
!58 = !DILocation(line: 22, column: 9, scope: !28)
!59 = !DILocalVariable(name: "i", scope: !60, file: !2, line: 23, type: !31)
!60 = distinct !DILexicalBlock(scope: !28, file: !2, line: 23, column: 5)
!61 = !DILocation(line: 23, column: 14, scope: !60)
!62 = !DILocation(line: 23, column: 10, scope: !60)
!63 = !DILocation(line: 23, column: 21, scope: !64)
!64 = distinct !DILexicalBlock(scope: !60, file: !2, line: 23, column: 5)
!65 = !DILocation(line: 23, column: 23, scope: !64)
!66 = !DILocation(line: 23, column: 5, scope: !60)
!67 = !DILocation(line: 24, column: 13, scope: !68)
!68 = distinct !DILexicalBlock(scope: !69, file: !2, line: 24, column: 13)
!69 = distinct !DILexicalBlock(scope: !64, file: !2, line: 23, column: 33)
!70 = !DILocation(line: 24, column: 13, scope: !69)
!71 = !DILocation(line: 24, column: 41, scope: !72)
!72 = distinct !DILexicalBlock(scope: !68, file: !2, line: 24, column: 39)
!73 = !DILocation(line: 25, column: 15, scope: !69)
!74 = !DILocation(line: 25, column: 12, scope: !69)
!75 = !DILocation(line: 27, column: 5, scope: !69)
!76 = !DILocation(line: 23, column: 29, scope: !64)
!77 = !DILocation(line: 23, column: 5, scope: !64)
!78 = distinct !{!78, !66, !79, !56}
!79 = !DILocation(line: 27, column: 5, scope: !60)
!80 = !DILocation(line: 29, column: 5, scope: !28)
!81 = !DILocation(line: 30, column: 1, scope: !28)
