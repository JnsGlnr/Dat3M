	.headerflags	@"EF_CUDA_TEXMODE_UNIFIED EF_CUDA_64BIT_ADDRESS EF_CUDA_SM89 EF_CUDA_VIRTUAL_SM(EF_CUDA_SM89)"
	.elftype	@"ET_EXEC"


//--------------------- .debug_frame              --------------------------
	.section	.debug_frame,"",@progbits
.debug_frame:
        /*0000*/ 	.byte	0xff, 0xff, 0xff, 0xff, 0x24, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff, 0xff, 0xff, 0xff
        /*0010*/ 	.byte	0xff, 0xff, 0xff, 0xff, 0x03, 0x00, 0x04, 0x7c, 0xff, 0xff, 0xff, 0xff, 0x0f, 0x0c, 0x81, 0x80
        /*0020*/ 	.byte	0x80, 0x28, 0x00, 0x08, 0xff, 0x81, 0x80, 0x28, 0x08, 0x81, 0x80, 0x80, 0x28, 0x00, 0x00, 0x00
        /*0030*/ 	.byte	0xff, 0xff, 0xff, 0xff, 0x34, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        /*0040*/ 	.byte	0x00, 0x00, 0x00, 0x00
        /*0044*/ 	.dword	_Z6kernelPiS_S_
        /*004c*/ 	.byte	0x80, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0x04, 0x00, 0x00, 0x00, 0x04, 0x34, 0x00
        /*005c*/ 	.byte	0x00, 0x00, 0x0c, 0x81, 0x80, 0x80, 0x28, 0x00, 0x04, 0xfc, 0xff, 0xff, 0x3f, 0x00, 0x00, 0x00
        /*006c*/ 	.byte	0x00, 0x00, 0x00, 0x00


//--------------------- .nv.info                  --------------------------
	.section	.nv.info,"",@"SHT_CUDA_INFO"
	.align	4


	//----- nvinfo : EIATTR_REGCOUNT
	.align		4
        /*0000*/ 	.byte	0x04, 0x2f
        /*0002*/ 	.short	(.L_1 - .L_0)
	.align		4
.L_0:
        /*0004*/ 	.word	index@(_Z6kernelPiS_S_)
        /*0008*/ 	.word	0x0000000e


	//----- nvinfo : EIATTR_MAX_STACK_SIZE
	.align		4
.L_1:
        /*000c*/ 	.byte	0x04, 0x23
        /*000e*/ 	.short	(.L_3 - .L_2)
	.align		4
.L_2:
        /*0010*/ 	.word	index@(_Z6kernelPiS_S_)
        /*0014*/ 	.word	0x00000000


	//----- nvinfo : EIATTR_MIN_STACK_SIZE
	.align		4
.L_3:
        /*0018*/ 	.byte	0x04, 0x12
        /*001a*/ 	.short	(.L_5 - .L_4)
	.align		4
.L_4:
        /*001c*/ 	.word	index@(_Z6kernelPiS_S_)
        /*0020*/ 	.word	0x00000000


	//----- nvinfo : EIATTR_FRAME_SIZE
	.align		4
.L_5:
        /*0024*/ 	.byte	0x04, 0x11
        /*0026*/ 	.short	(.L_7 - .L_6)
	.align		4
.L_6:
        /*0028*/ 	.word	index@(_Z6kernelPiS_S_)
        /*002c*/ 	.word	0x00000000
.L_7:


//--------------------- .nv.info._Z6kernelPiS_S_  --------------------------
	.section	.nv.info._Z6kernelPiS_S_,"",@"SHT_CUDA_INFO"
	.align	4


	//----- nvinfo : EIATTR_CUDA_API_VERSION
	.align		4
        /*0000*/ 	.byte	0x04, 0x37
        /*0002*/ 	.short	(.L_9 - .L_8)
.L_8:
        /*0004*/ 	.word	0x00000078


	//----- nvinfo : EIATTR_PARAM_CBANK
	.align		4
.L_9:
        /*0008*/ 	.byte	0x04, 0x0a
        /*000a*/ 	.short	(.L_11 - .L_10)
	.align		4
.L_10:
        /*000c*/ 	.word	index@(.nv.constant0._Z6kernelPiS_S_)
        /*0010*/ 	.short	0x0160
        /*0012*/ 	.short	0x0018


	//----- nvinfo : EIATTR_CBANK_PARAM_SIZE
	.align		4
.L_11:
        /*0014*/ 	.byte	0x03, 0x19
        /*0016*/ 	.short	0x0018


	//----- nvinfo : EIATTR_KPARAM_INFO
	.align		4
        /*0018*/ 	.byte	0x04, 0x17
        /*001a*/ 	.short	(.L_13 - .L_12)
.L_12:
        /*001c*/ 	.word	0x00000000
        /*0020*/ 	.short	0x0002
        /*0022*/ 	.short	0x0010
        /*0024*/ 	.byte	0x00, 0xf0, 0x21, 0x00


	//----- nvinfo : EIATTR_KPARAM_INFO
	.align		4
.L_13:
        /*0028*/ 	.byte	0x04, 0x17
        /*002a*/ 	.short	(.L_15 - .L_14)
.L_14:
        /*002c*/ 	.word	0x00000000
        /*0030*/ 	.short	0x0001
        /*0032*/ 	.short	0x0008
        /*0034*/ 	.byte	0x00, 0xf0, 0x21, 0x00


	//----- nvinfo : EIATTR_KPARAM_INFO
	.align		4
.L_15:
        /*0038*/ 	.byte	0x04, 0x17
        /*003a*/ 	.short	(.L_17 - .L_16)
.L_16:
        /*003c*/ 	.word	0x00000000
        /*0040*/ 	.short	0x0000
        /*0042*/ 	.short	0x0000
        /*0044*/ 	.byte	0x00, 0xf0, 0x21, 0x00


	//----- nvinfo : EIATTR_MAXREG_COUNT
	.align		4
.L_17:
        /*0048*/ 	.byte	0x03, 0x1b
        /*004a*/ 	.short	0x00ff


	//----- nvinfo : EIATTR_EXIT_INSTR_OFFSETS
	.align		4
        /*004c*/ 	.byte	0x04, 0x1c
        /*004e*/ 	.short	(.L_19 - .L_18)


	//   ....[0]....
.L_18:
        /*0050*/ 	.word	0x000000d0
.L_19:


//--------------------- .nv.constant0._Z6kernelPiS_S_ --------------------------
	.section	.nv.constant0._Z6kernelPiS_S_,"a",@progbits
	.align	4
.nv.constant0._Z6kernelPiS_S_:
	.zero		376


//--------------------- .text._Z6kernelPiS_S_     --------------------------
	.section	.text._Z6kernelPiS_S_,"ax",@progbits
	.sectioninfo	@"SHI_REGISTERS=14"
	.align	128
        .global         _Z6kernelPiS_S_
        .type           _Z6kernelPiS_S_,@function
        .size           _Z6kernelPiS_S_,(.L_x_1 - _Z6kernelPiS_S_)
        .other          _Z6kernelPiS_S_,@"STO_CUDA_ENTRY STV_DEFAULT"
_Z6kernelPiS_S_:
.text._Z6kernelPiS_S_:
        /*0000*/                   MOV R1, c[0x0][0x28] ;
        /*0010*/                   S2R R9, SR_CTAID.X ;
        /*0020*/                   MOV R6, 0x4 ;
        /*0030*/                   ULDC.64 UR4, c[0x0][0x118] ;
        /*0040*/                   S2R R0, SR_TID.X ;
        /*0050*/                   IMAD R9, R9, c[0x0][0x0], R0 ;
        /*0060*/                   IMAD.WIDE R2, R9, R6, c[0x0][0x160] ;
        /*0070*/                   LDG.E R2, [R2.64] ;
        /*0080*/                   IMAD.WIDE R4, R9, R6, c[0x0][0x168] ;
        /*0090*/                   IMAD.WIDE R6, R9.reuse, R6, c[0x0][0x170] ;
        /*00a0*/                   STG.E [R4.64], R9 ;
        /*00b0*/                   IADD3 R11, R9, R2, RZ ;
        /*00c0*/                   STG.E [R6.64], R11 ;
        /*00d0*/                   EXIT ;
.L_x_0:
        /*00e0*/                   BRA `(.L_x_0);
        /*00f0*/                   NOP;
        /*0100*/                   NOP;
        /*0110*/                   NOP;
        /*0120*/                   NOP;
        /*0130*/                   NOP;
        /*0140*/                   NOP;
        /*0150*/                   NOP;
        /*0160*/                   NOP;
        /*0170*/                   NOP;
.L_x_1:
