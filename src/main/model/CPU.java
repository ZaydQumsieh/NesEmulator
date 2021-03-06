package model;

import ppu.PPU;
import ui.window.CpuOutput;
import ui.window.CpuViewer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

// Class CPU:
//     Models the 6502 CPU in the NES. Performs all the legal opcodes that are provided with the NES, and completes
//     them in a cycle-accurate manner. One deficiency, though, is that cycles are after the instruction is completed,
//     rather than after each read/write access, like the actual CPU. This can be solved with a state machine but would
//     require a major code rewrite. As this is a major class in the file, I'll provide a detailed description below
//     of what the class contains.
//
// Contains:
//     Registers:
//         registerA     (the Accumulator)
//         registerX     (the X Register)
//         registerY     (the Y Register)
//         registerPC    (the Program Counter)
//         registerS     (the Stack Pointer)
//
//     Flags:
//         flagC         (the carry flag)
//         flagZ         (the zero flag)
//         flagI         (the interrupt disable flag)
//         flagD         (the decimal mode flag)
//         flagB         (the break flag)
//         flagV         (the overflow flag)
//         flagN         (the negative flag)
//
//     Memory:
//         ram
//         mapper        (the exact type of mapper is cartridge-specific)
//
//     Other:
//         cycles        (the current cycle # of the CPU)
//         enabled       (true if enabled, disabled by STP)

public class CPU {
    // Constants
    private static final int RAM_SIZE                 = (int) Math.pow(2, 11);

    public static final int INITIAL_REGISTER_A        = 0x0000;
    public static final int INITIAL_REGISTER_X        = 0x0000;
    public static final int INITIAL_REGISTER_Y        = 0x0000;
    public static final int INITIAL_REGISTER_PC       = 0xC000;
    public static final int INITIAL_REGISTER_S        = 0x00FD;

    public static final int MINIMUM_REGISTER_A        = 0x0000;
    public static final int MINIMUM_REGISTER_X        = 0x0000;
    public static final int MINIMUM_REGISTER_Y        = 0x0000;
    public static final int MINIMUM_REGISTER_PC       = 0x0000;
    public static final int MINIMUM_REGISTER_S        = 0x0000;
    public static final int MINIMUM_CYCLES            = 0;
    
    public static final int MAXIMUM_REGISTER_A        = 0x00FF;
    public static final int MAXIMUM_REGISTER_X        = 0x00FF;
    public static final int MAXIMUM_REGISTER_Y        = 0x00FF;
    public static final int MAXIMUM_REGISTER_PC       = 0xFFFF;
    public static final int MAXIMUM_REGISTER_S        = 0x00FF;
    public static final int MAXIMUM_CYCLES            = 340;

    public static final int OFFSET_REGISTER_A         = 0x0000;
    public static final int OFFSET_REGISTER_X         = 0x0000;
    public static final int OFFSET_REGISTER_Y         = 0x0000;
    public static final int OFFSET_REGISTER_PC        = 0xC000;
    public static final int OFFSET_REGISTER_S         = 0x0100;

    public static final int INITIAL_CYCLES            = 0x0007;
    public static final int INITIAL_RAM_STATE         = 0x0000;

    public static final int REGISTER_A_ADDRESS        = 0x10000;

    // CPU Flags
    protected int flagC;  // Carry
    protected int flagZ;  // Zero
    protected int flagI;  // Interrupt Disable
    protected int flagD;  // Decimal
    protected int flagB;  // Break
    // 7 flags in one byte; positions 4/5 are empty. flagB is not included in the status.
    protected int flagV;  // Overflow
    protected int flagN;  // Negative

    // Registers / Cycles
    private int registerA;  // Accumulator for ALU
    private int registerX;  // Index
    private int registerY;  // Index
    private int registerPC; // The program counter
    private int registerS;  // The stack pointer

    // these two values are editted by the addressing modes and are passed into the opcodes.
    private int currentInstructionPointer;
    private int currentInstructionValue;

    // and this one is passed into the addressing modes. declared here so there are no memory allocations during
    // processInstruction() and cycle().
    private int[] modeArguments; // c style static array
    private int modeArgumentsSize;

    private boolean enabled;
    private int cycle;
    int cyclesRemaining;
    private ArrayList<Integer> breakpoints;
    protected boolean nmi;

    // Memory
    protected int[] ram;

    private CpuOutput loggingOutput;

    private boolean dma;
    private int     dmaPage;
    private int     dmaIndex;

    // EFFECTS: initializes the RAM and STACK and calls reset() to reset all values in the cpu to their default states.
    public CPU() {
        init();
    }

    // MODIFIES: ram
    // EFFECTS: initializes the RAM and STACK with their appropriate sizes.
    private void init() {
        ram = new int[CPU.RAM_SIZE];
    }

    // MODIFIES: registerA, registerX, registerY, registerPC, registerS, cycles, ram
    // EFFECTS: resets all values in the cpu (registers, cycles, ram, stack) to their default states. Enables the CPU.
    void reset() {
        registerA   = CPU.INITIAL_REGISTER_A;
        registerX   = CPU.INITIAL_REGISTER_X;
        registerY   = CPU.INITIAL_REGISTER_Y;
        registerPC  = CPU.INITIAL_REGISTER_PC;
        registerS   = CPU.INITIAL_REGISTER_S;

        cyclesRemaining = 0;
        cycle = CPU.INITIAL_CYCLES;
        breakpoints = new ArrayList<>();

        // Note: ram state and stack pointer considered unreliable after reset.
        for (int i = 0; i < ram.length; i++) {
            ram[i] = CPU.INITIAL_RAM_STATE;
        }

        int byteOne = readMemory(0xFFFC);
        int byteTwo = readMemory(0xFFFD);
        setRegisterPC(byteOne + byteTwo * 256);
        //setRegisterPC(0xC000);     // Uncomment for nestest
        enabled  = true;
        dma      = false;
        dmaPage  = 0;
        dmaIndex = 0;

        modeArguments = new int[2];
    }


    Instant previousSecond = Instant.now();
    int frames = 0;

    // MODIFIES: All registers, all flags, the ram, the stack, and the mapper may change.
    // EFFECTS: Cycles the cpu through one instruction, and updates the cpu's state as necessary.
    public void cycle() {
        frames++;
        if (ChronoUnit.MILLIS.between(previousSecond, Instant.now()) >= 1 * 1000) {
            previousSecond = Instant.now();
            System.out.println("cpuwu: " + frames);
            frames = 0;
        }

        if (!dma) {
            handleNMI();

            if (cyclesRemaining <= 1) {
                processInstruction();
            } else {
                cyclesRemaining--;
            }
        } else {
            handleDMA();
        }

        incrementCycles(1);
    }

    // MODIFIES: this, bus
    // EFFECTS:  runs one cycle of DMA in the CPU, transfering the memory in 0x[dmaPage]00 to 0x[dmaPage]FF to the
    //           PPU primary OAM.
    private void handleDMA() {
        //System.out.println(dmaIndex);
        if (dmaIndex == 0) {
            if ((cycle & 1) == 0) {
                dmaIndex++;
            }
        } else {
            if ((dmaIndex & 1) == 0) {
                int value = readMemory((dmaPage << 8) + (dmaIndex - 1) / 2);
                Bus.getInstance().ppuWrite(PPU.OAMDATA_ADDRESS, value);
                if (dmaIndex == 512) {
                    dma = false;
                    return;
                }
            }
            dmaIndex++;
        }
    }

    // MODIFIES: cyclesRemaining
    // EFFECTS:  increments cyclesRemaining by increment.
    public void incrementCyclesRemaining(int increment) {
        // System.out.println("Incremented +" + increment + " to " + cyclesRemaining + "!");
        cyclesRemaining += increment;
    }

    // MODIFIES: processes one instruction and updates the CPU's state as necessary. An instruction is only considered
    //           complete once the appropriate amount of cycles have been run through.
    public void processInstruction() {
        if (isBreakpoint(registerPC)) {
            setEnabled(false);
        }

        int valueAtProgramCounter = readMemory(registerPC);
        Instruction instruction = Instruction.getInstructions().get(valueAtProgramCounter);
        //cyclesRemaining = instruction.getNumCycles();

        modeArgumentsSize = instruction.getNumArguments();
        for (int i = 0; i < instruction.getNumArguments(); i++) {
            modeArguments[i] = readMemory(registerPC + i + 1);
        }

        registerPC += instruction.getNumArguments() + 1;

        //System.out.println(preStatus);
        //System.out.println(instruction.toString());
        Mode.runMode(instruction.getMode(), modeArguments, modeArgumentsSize, this);
        Opcode.runOpcode(instruction.getOpcode(), currentInstructionPointer, this);
        //incrementCycles(instruction.getNumCycles());
        //loggingOutput.log(preStatus);
        incrementCyclesRemaining(instruction.getNumCycles()); //*/
    }

    // MODIFIES: this
    // EFFECTS:  handles the NMI (non-maskable interrupt). If the NMI flag is set, interrupts the CPU and sets
    //           registerPC to the vector at 0xFFFA/B
    private void handleNMI() {
        if (nmi) {
            int byteOne = ((getRegisterPC()) & 0b1111111100000000) >> 8;
            int byteTwo = ((getRegisterPC()) & 0b0000000011111111);
            pushStack(byteOne);
            pushStack(byteTwo);
            pushStack(getStatus());

            byteOne = readMemory(0xFFFA);
            byteTwo = readMemory(0xFFFB);
            setRegisterPC(byteTwo * 256 + byteOne);

            nmi = false;
            cyclesRemaining = 8;
        }
    }

    // REQUIRES: address is in between 0x0000 and 0x10000, inclusive.
    // EFFECTS: returns the value of the memory at the given address.
    //          see the table below for a detailed description of what is stored at which address.
    //          https://wiki.nesdev.com/w/index.php/CPU_memory_map
    //          ADDRESS RANGE | SIZE  | DEVICE
    //          $0000 - $07FF | $0800 | 2KB internal RAM
    //          $0800 - $0FFF | $0800 |
    //          $1000 - $17FF | $0800 | Mirrors of $0000-$07FF
    //          $1800 - $1FFF | $0800 |
    //          $2000 - $2007 | $0008 | NES PPU registers
    //          $2008 - $3FFF | $1FF8 | Mirrors of $2000-$2007 (repeats every 8 bytes)
    //          $4000 - $4017 | $0018 | NES APU and I/O registers
    //          $4018 - $401F | $0008 | APU and I/O functionality that is normally disabled.
    //          $4020 - $FFFF | $BFE0 | Cartridge space: PRG ROM, PRG RAM, and mapper registers
    public int readMemory(int pointer) {
        if        (pointer <= 0x1FFF) {        // 2KB internal RAM  + its mirrors
            while (pointer >= 0x0800) {
                pointer -= 0x0800;
            }
            return ram[pointer];
        } else if (pointer <= 0x3FFF) {        // NES PPU registers + its mirrors
            return Bus.getInstance().ppuRead(Util.getNthBits(pointer, 0, 3) + 0x2000);
        } else if (pointer <= 0x4013) {
            return 0; // TODO: apu read
        } else if (pointer <= 0x4014) {
            return Bus.getInstance().ppuRead(pointer);
        } else if (pointer <= 0x4015) {
            return 0; // TODO: apu read
        } else if (pointer <= 0x4016) {
            return Bus.getInstance().controllerRead(pointer);
        } else if (pointer <= 0x4017) {       // NES APU and I/O registers
            return Bus.getInstance().controllerRead(pointer);
        } else if (pointer <= 0x401F) {       // APU and I/O functionality (normally disabled)
            return 0; // TODO add when the apu is implemented.
        } else if (pointer <= 0xFFFF) {
            return Bus.getInstance().mapperReadCpu(pointer);
        } else {
            return registerA;
        }
    }

    // REQUIRES: address is in between 0x0000 and 0x10000, inclusive.
    // MODIFIES: ram
    // EFFECTS: check the table below for a detailed explanation of what is affected and how.
    //          https://wiki.nesdev.com/w/index.php/CPU_memory_map
    //          ADDRESS RANGE | SIZE  | DEVICE
    //          $0000 - $07FF | $0800 | 2KB internal RAM
    //          $0800 - $0FFF | $0800 |
    //          $1000 - $17FF | $0800 | Mirrors of $0000-$07FF
    //          $1800 - $1FFF | $0800 |
    //          $2000 - $2007 | $0008 | NES PPU registers
    //          $2008 - $3FFF | $1FF8 | Mirrors of $2000-$2007 (repeats every 8 bytes)
    //          $4000 - $4017 | $0018 | NES APU and I/O registers
    //          $4018 - $401F | $0008 | APU and I/O functionality that is normally disabled.
    //          $4020 - $FFFF | $BFE0 | Cartridge space: PRG ROM, PRG RAM, and mapper registers
    public void writeMemory(int pointer, int value) {
        if        (pointer <= 0x1FFF) {        // 2KB internal RAM  + its mirrors
            ram[pointer & 0x07FF] = value;
        } else if (pointer <= 0x3FFF) {        // NES PPU registers + its mirrors
            Bus.getInstance().ppuWrite(Util.getNthBits(pointer, 0, 3) + 0x2000, value);
        } else if (pointer <= 0x4013) {
            Bus.getInstance().apuChannelWrite(pointer, value);
        } else if (pointer <= PPU.OAMDMA_ADDRESS) {
            startDMA(value);
        } else if (pointer <= 0x4015) {
            Bus.getInstance().apuWrite(pointer, value);
        } else if (pointer <= 0x4016) {
            Bus.getInstance().controllerWrite(pointer, value);
        } else if (pointer <= 0x4017) {       // NES APU and I/O registers.
            Bus.getInstance().apuWrite(pointer, value);
            Bus.getInstance().controllerWrite(pointer, value);
        } else if (pointer <= 0x401F) {       // APU and I/O functionality that is
                                                                             // normally disabled
            // TODO add when the apu is implemented.
        } else if (pointer <= 0xFFFF) {
            Bus.getInstance().mapperWrite(pointer, value);
        } else {
            registerA = value;
        }
    }

    private void startDMA(int value) {
        dma      = true;
        dmaPage  = value;
        dmaIndex = 0;
    }
/*
    private void writeIORegisters(int pointer, int value) {
        System.out.println("Wrote " + Integer.toBinaryString(value) + " to 0x" + Integer.toHexString(pointer));
        if (pointer == 0x4016) {
            controller.setPolling(value == 1);
        }
    }*/

    // REQUIRES: 0 <= value < 2^8
    // MODIFIES: registerS, stack
    // EFFECTS: value is pushed onto the stack, registerS is decremented.
    public void pushStack(int value) {
        writeMemory(CPU.OFFSET_REGISTER_S + registerS, value);

        setRegisterS(getRegisterS() - 1);
    }

    // MODIFIES: registerS, stack
    // EFFECTS: value is pulled from the stack and returned, registerS is incremented.
    public int pullStack() {
        setRegisterS(getRegisterS() + 1);

        return readMemory(CPU.OFFSET_REGISTER_S + registerS);
    }

    // EFFECTS: peeks into the stack.
    public int peekStack() {
        return readMemory(CPU.OFFSET_REGISTER_S + registerS + 1);
    }

    // REQUIRES: status can be represented as an 8bit binary integer
    // EFFECTS: use the flags to construct the status by concatenating them like this:
    //          VN11DIZC where the 4th and 5th bits (little endian) are 1.
    public int getStatus() {
        return (int) (getFlagC() << 0)
             + (int) (getFlagZ() << 1)
             + (int) (getFlagI() << 2)
             + (int) (getFlagD() << 3)
             + (int) (1          << 4)
             + (int) (1          << 5) // bit 5 in the flags byte is empty
             + (int) (getFlagV() << 6)
             + (int) (getFlagN() << 7);
    }

    // EFFECTS: returns whether or not the address is a breakpoint
    public boolean isBreakpoint(int breakpoint) {
        for (int address : breakpoints) {
            if (address == breakpoint) {
                return true;
            }
        }

        return false;
    }

    // REQUIRES: status can be represented as an 8bit binary integer
    // MODIFIES: sets the flags in this way:
    // flagC is the 0th bit of status
    // flagZ is the 1st bit of status
    // flagI is the 2nd bit of status
    // flagD is the 3rd bit of status
    //          the 4th bit is disregarded
    //          the 5th bit is disregarded
    // flagV is the 6th bit of status
    // flagN is the 7th bit of status
    public void setStatus(int status) {
        setFlagC(Util.getNthBit(status, 0));
        setFlagZ(Util.getNthBit(status, 1));
        setFlagI(Util.getNthBit(status, 2));
        setFlagD(Util.getNthBit(status, 3));
        // bit 4 in the flags byte is disregarded
        // bit 5 in the flags byte is disregarded
        setFlagV(Util.getNthBit(status, 6));
        setFlagN(Util.getNthBit(status, 7));
    }

    // EFFECTS: returns the C flag
    public int getFlagC() {
        return flagC;
    }

    // EFFECTS: returns the Z flag
    public int getFlagZ() {
        return flagZ;
    }

    // EFFECTS: returns the I flag
    public int getFlagI() {
        return flagI;
    }

    // EFFECTS: returns the D flag
    public int getFlagD() {
        return flagD;
    }

    // EFFECTS: returns the B flag
    public int getFlagB() {
        return flagB;
    }

    // EFFECTS: returns the V flag
    public int getFlagV() {
        return flagV;
    }

    // EFFECTS: returns the N flag
    public int getFlagN() {
        return flagN;
    }

    // EFFECTS: returns the A Register
    public int getRegisterA() {
        return registerA;
    }

    // EFFECTS: returns the X Register
    public int getRegisterX() {
        return registerX;
    }

    // EFFECTS: returns the Y Register
    public int getRegisterY() {
        return registerY;
    }

    // EFFECTS: returns the PC Register (program counter)
    public int getRegisterPC() {
        return registerPC;
    }

    // EFFECTS: returns the S Register (stack pointer)
    public int getRegisterS() {
        return registerS;
    }

    // EFFECTS: returns whether or not the CPU is enabled.
    public boolean isEnabled() {
        return enabled;
    }

    // EFFECTS: returns the number of cycles
    public int getCycles() {
        return cycle;
    }

    // MODIFIES: registerA
    // EFFECTS: sets registerA to a new value wrapped around (0...MAXIMUM_REGISTER_A_VALUE)
    // example: setRegisterA(256) sets registerS to 0.
    // example: setRegisterA(-1)  sets registerS to MAXIMUM_REGISTER_A_VALUE - 1.
    public void setRegisterA(int registerA) {
        this.registerA = registerA;
    }

    // MODIFIES: registerX
    // EFFECTS: sets registerX to a new value wrapped around (0...MAXIMUM_REGISTER_X_VALUE)
    // example: setRegisterX(256) sets registerS to 0.
    // example: setRegisterX(-1)  sets registerS to MAXIMUM_REGISTER_X_VALUE - 1.
    public void setRegisterX(int registerX) {
        this.registerX = registerX;
    }

    // MODIFIES: registerY
    // EFFECTS: sets registerY to a new value wrapped around (0...MAXIMUM_REGISTER_Y_VALUE)
    // example: setRegisterY(256) sets registerY to 0.
    // example: setRegisterY(-1)  sets registerY to MAXIMUM_REGISTER_Y_VALUE - 1.
    public void setRegisterY(int registerY) {
        this.registerY = registerY;
    }

    // MODIFIES: registerPC
    // EFFECTS: sets registerPC to a new value wrapped around REGISTER_PC_OFFSET + (0...MAXIMUM_REGISTER_PC_VALUE)
    public void setRegisterPC(int registerPC) {
        this.registerPC = registerPC;
    }

    // MODIFIES: registerS
    // EFFECTS: sets registerS to a new value wrapped around (0...MAXIMUM_REGISTER_S_VALUE)
    // example: setRegisterS(256) sets registerS to 0.
    // example: setRegisterS(-1)  sets registerS to MAXIMUM_REGISTER_S_VALUE - 1.
    public void setRegisterS(int registerS) {
        this.registerS = registerS;
    }

    // REQUIRES: flagC is either 0 or 1. Note: boolean is not used because calculations are more readable when
    // the flags are either 0 or 1.
    // MODIFIES: flagC
    // EFFECTS: sets flagC to the given value
    public void setFlagC(int flagC) {
        this.flagC = flagC;
    }

    // REQUIRES: flagZ is either 0 or 1. Note: boolean is not used because calculations are more readable when
    // the flags are either 0 or 1.
    // MODIFIES: flagZ
    // EFFECTS: sets flagZ to the given value
    public void setFlagZ(int flagZ) {
        this.flagZ = flagZ;
    }

    // REQUIRES: flagI is either 0 or 1. Note: boolean is not used because calculations are more readable when
    // the flags are either 0 or 1.
    // MODIFIES: flagI
    // EFFECTS: sets flagI to the given value
    public void setFlagI(int flagI) {
        this.flagI = flagI;
    }

    // REQUIRES: flagD is either 0 or 1. Note: boolean is not used because calculations are more readable when
    // the flags are either 0 or 1.
    // MODIFIES: flagD
    // EFFECTS: sets flagD to the given value
    public void setFlagD(int flagD) {
        this.flagD = flagD;
    }

    // REQUIRES: flagB is either 0 or 1. Note: boolean is not used because calculations are more readable when
    // the flags are either 0 or 1.
    // MODIFIES: flagB
    // EFFECTS: sets flagB to the given value
    public void setFlagB(int flagB) {
        this.flagB = flagB;
    }

    // REQUIRES: flagV is either 0 or 1. Note: boolean is not used because calculations are more readable when
    // the flags are either 0 or 1.
    // MODIFIES: flagV
    // EFFECTS: sets flagV to the given value
    public void setFlagV(int flagV) {
        this.flagV = flagV;
    }

    // REQUIRES: flagN is either 0 or 1. Note: boolean is not used because calculations are more readable when
    // the flags are either 0 or 1.
    // MODIFIES: flagN
    // EFFECTS: sets flagN to the given value
    public void setFlagN(int flagN) {
        this.flagN = flagN;
    }

    // MODIFIES: enabled
    // EFFECTS: sets enabled to the given value.
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // MODIFIES: cycles
    // EFFECTS: increments the cycles by the given amount and wraps it around MINIMUM_CYCLES and MAXIMUM_CYCLES
    public void incrementCycles(int numCycles) {
        cycle += numCycles;
        //cycle = (cycle - MINIMUM_CYCLES) % (MAXIMUM_CYCLES - MINIMUM_CYCLES + 1) + MINIMUM_CYCLES;
    }

    // REQUIRES: breakpoint is an Address bounded between 0x0000 and 0xFFFF inclusive.
    // MODIFIES: breakpoints
    // EFFECTS: adds the breakpoint.
    public void addBreakpoint(int breakpoint) {
        breakpoints.add(breakpoint);
    }

    public void setLoggingOutput(CpuOutput cpuOutput) {
        this.loggingOutput = cpuOutput;
    }

    public ArrayList<Integer> getBreakpoints() {
        return breakpoints;
    }

    public void setCycles(Integer value) {
        this.cycle = value;
    }

    public int getCurrentInstructionPointer() {
        return currentInstructionPointer;
    }

    public void setCurrentInstructionPointer(int currentInstructionPointer) {
        this.currentInstructionPointer = currentInstructionPointer;
    }

    public int getCurrentInstructionValue() {
        if (currentInstructionPointer == -1) return currentInstructionValue;
        return readMemory(currentInstructionPointer);
    }

    public void setCurrentInstructionValue(int currentInstructionValue) {
        this.currentInstructionValue = currentInstructionValue;
    }
}
