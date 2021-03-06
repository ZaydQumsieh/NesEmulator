package model;

import apu.APU;
import mapper.Mapper;
import mapper.NRom;
import ppu.Mirroring;
import ppu.PPU;
import ui.CpuFileOutput;
import ui.controller.Controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

// Class Bus:
//     Bus is a class that manages the CPU, PPU, controller, and mapper. Serves as a way for these four components
//     to communicate with each other.

public class Bus {
    public static final int HEADER_SIZE           = 16;    // bytes
    public static final int TRAINER_SIZE          = 512;   // bytes
    public static final int PRG_ROM_SIZE          = 16384; // bytes
    public static final int CHR_ROM_SIZE          = 8192;  // bytes

    private CPU cpu;
    private PPU ppu;
    private APU apu;
    private Controller controller;
    private Mapper mapper;

    private boolean cartridgeLoaded;
    private boolean controllerConnected;

    private int trueCpuCycles;
    private int truePpuCycles;

    private boolean enabled;

    // I decided to make the bus initialization in a static block because we're almost always going to need a Bus when
    // running the program
    private static Bus bus;

    static {
        hardReset();
    }

    // MODIFIES: this
    // EFFECTS:  initializes the CPU and PPU and connects them to the bus (this).
    private Bus() {
        cpu = new CPU();
        ppu = new PPU();
        apu = new APU();

        cpu.setLoggingOutput(new CpuFileOutput());

        cartridgeLoaded     = false;
        controllerConnected = false;
        enabled             = true;
        trueCpuCycles       = 0;
        truePpuCycles       = 0;
    }

    public static Bus getInstance() {
        return bus;
    }

    // MODIFIES: this
    // EFFECTS:  hard resets the bus; the bus is now back at its default state.
    public static void hardReset() {
        bus = new Bus();
    }

    // MODIFIES: this
    // EFFECTS:  loads the cartridge into the mapper and resets the cpu and ppu
    public void loadCartridge(File file) throws IOException {
        readCartridge(file);
        cartridgeLoaded = true;

        softReset();
    }

    // MODIFIES: cpu, ppu
    // EFFECTS:  soft resets the cpu and ppu - unlike a hard reset, this doesn't reset everything.
    public void softReset() {
        cpu.reset();
        ppu.reset();
        apu.enable();
    }

    // MODIFIES: mapper
    // EFFECTS:  reads the cartridge and sets the mapper's prgRom and chrRom according to the data read.
    private void readCartridge(File file) throws IOException {
        // https://wiki.nesdev.com/w/index.php/INES
        // iNES file format:
        // SIZE            | DEVICE
        // 16 bytes        | Header
        // 0 or 512 bytes  | Trainer
        // 16384 * x bytes | PRG ROM data
        // 8192 * y bytes  | CHR ROM data
        FileInputStream fileInputStream = new FileInputStream(file);
        int[] header = readFile(fileInputStream, 0, 0, HEADER_SIZE);

        boolean trainerPresent = Util.getNthBit(header[6], 2) == 1;
        int[] trainer = readFile(fileInputStream, 0, 0, trainerPresent ? TRAINER_SIZE : 0);

        int[] prgRom = readFile(fileInputStream, 0, 8 * 4096,header[4] * PRG_ROM_SIZE);
        int[] chrRom = readFile(fileInputStream, 0, 0, header[5] * CHR_ROM_SIZE);
        mapper = new NRom(prgRom, chrRom);
        ppu.setNametableMirroring(Mirroring.HORIZONTAL);
    }

    // REQUIRES: file has at least numBytes available, otherwise throws IOException.
    // MODIFIES: file now has numBytes less bytes available
    // EFFECTS: wrapper class for FileInputStream.read(). returns int[] result instead of bytes[] by reading
    //          numBytes from the file with the specified offset.
    public int[] readFile(FileInputStream file, int offset, int pointerOffset, int numBytes) throws IOException {
        int[] result = new int[numBytes];
        for (int i = offset; i < offset + numBytes; i++) {
            result[i - offset] = file.read();
        }
        return result;
    }






    // REQUIRES: logFile is open.
    // MODIFIES: cpu, logfile
    // EFFECTS: cycles the cpu through one instruction, throws IOException if logfile has been closed.
    public void cycle() {
        if (!cartridgeLoaded || !enabled) {
            return;
        }

        /* uncomment for cycle check
        boolean doCheck = cpu.cyclesRemaining == 1;
        if (doCheck) {
            System.out.println(cpu.getCycles() + " " + ppu.getCycles());
            int actual   = ppu.getCycles();
            int expected = Integer.parseInt(scanner1.nextLine().trim());
            if (actual != expected) {
                int u = 3;
            }

            actual   = cpu.getCycles();
            expected = Integer.parseInt(scanner2.nextLine().trim());
            if (actual != expected) {
                int u = 3;
            }
        }*/

        cycleComponents();
    }

    // MODIFIES: cpu, ppu
    // EFFECTS:  cycles the ppu 3 times and the cpu 1 time.
    public void cycleComponents() {
        ppu.cycle();
        ppu.cycle();
        ppu.cycle();
        cpu.cycle();

        ppu.cycle();
        ppu.cycle();
        ppu.cycle();
        cpu.cycle();

        apu.cycle();
    }





    // MODIFIES: ppu
    // EFFECTS:  reads the ppu at the given register and returns the value.
    public int ppuRead(int pointer) {
        return ppu.readRegister(pointer);
    }

    // REQUIRES: mapper is not null, caller is CPU
    // EFFECTS:  reads the mapper at the given pointer and returns the value.
    public int mapperReadCpu(int pointer) {
        try {
            return mapper.readMemoryCpu(pointer);
        } catch (NullPointerException e) {
            return 0;
        }
    }

    // REQUIRES: mapper is not null, caller is PPU
    // EFFECTS:  reads the mapper at the given pointer and returns the value.
    public int mapperReadPpu(int pointer) {
        try {
            return mapper.readMemoryPpu(pointer);
        } catch (NullPointerException e) {
            return 0;
        }
    }

    // MODIFIES: controller
    // EFFECTS:  reads the address in controller and returns the value.
    public int controllerRead(int pointer) {
        if (controllerConnected && pointer == 0x4016) {
            int address = controller.poll();
            // System.out.println("POLLED: " + address);
            return address;
        } else {
            return 0;
        }
    }

    // MODIFIES: ppu
    // EFFECTS:  writes the ppu register at the given pointer to the value.
    public void ppuWrite(int pointer, int value) {
        ppu.writeRegister(pointer, value);
    }

    // MODIFIES: apu
    // EFFECTS:  writes the apu channel register at the given pointer to the value.
    public void apuChannelWrite(int pointer, int value) {
        apu.writeChannelMemory(pointer, value);
    }

    // MODIFIES: apu
    // EFFECTS:  writes the apu register at the given pointer to the value.
    public void apuWrite(int pointer, int value) {
        apu.writeMemory(pointer, value);
    }

    // EFFECTS:  writes the mapper at the given pointer to the value.
    public void mapperWrite(int pointer, int value) {
        mapper.writeMemory(pointer, value);
    }

    // MODIFIES: controller
    // EFFECTS:  writes the controller at the given pointer to the value.
    public void controllerWrite(int pointer, int value) {
        if (controllerConnected && pointer == 0x4016) {
            // System.out.println("STROBE: " + value);
            controller.setPolling(value == 1);
        }
    }

    public void setNmi(boolean nmi) {
        cpu.nmi = nmi;
    }

    public PPU getPpu() {
        return ppu;
    }

    // MODIFIES: cpu, ppu, mapper, cartridgeLoaded
    // EFFECTS:  sets the cpu, ppu, and mapper to the given values and sets cartridgeLoaded to true
    public void reload(CPU cpu, PPU ppu, Mapper mapper) {
        this.cpu    = cpu;
        this.ppu    = ppu;
        this.mapper = mapper;

        cartridgeLoaded = true;
    }

    // MODIFIES: controller
    // EFFECTS:  connects the controller to the bus.
    public void setController(Controller controller) {
        this.controller = controller;
        controllerConnected = true;
    }

    public CPU getCpu() {
        return cpu;
    }

    public Mapper getMapper() {
        return mapper;
    }

    public Controller getController() {
        return controller;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean getEnabled() {
        return enabled;
    }

    public boolean getCartridgeLoaded() {
        return cartridgeLoaded;
    }

    public boolean getControllerConnected() {
        return controllerConnected;
    }

    public void ppuDma(int value) {
        ppu.writeOam(value);
    }

    public APU getApu() {
        return apu;
    }

    public void startDataLines() {
        apu.startDataLines();
    }
}
