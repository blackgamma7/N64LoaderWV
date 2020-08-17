/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package n64loaderwv;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.python.jline.internal.Log;

import ghidra.app.util.MemoryBlockUtils;
import ghidra.app.util.Option;
import ghidra.app.util.bin.BinaryReader;
import ghidra.app.util.bin.ByteArrayProvider;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.AbstractLibrarySupportLoader;
import ghidra.app.util.opinion.LoadSpec;
import ghidra.framework.model.DomainObject;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataUtilities;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.DataUtilities.ClearDataMode;
import ghidra.program.model.lang.LanguageCompilerSpecPair;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.SymbolUtilities;
import ghidra.util.Msg;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.TaskMonitor;

public class N64LoaderWVLoader extends AbstractLibrarySupportLoader {

	class BlockInfo
	{
		long start, end;
		String desc, name;
		BlockInfo(long s, long e, String n, String d)
		{
			start = s;
			end = e;
			desc = d;
			name = n;
		}
	}
	
	ArrayList<MemoryBlock> blocks = new ArrayList<MemoryBlock>();
	ArrayList<BlockInfo> initSections = new ArrayList<N64LoaderWVLoader.BlockInfo>()
	{
		{
			//add(new BlockInfo(0x00000000, 0x03EFFFFF, "RDRAM Memory",".rdram"));
			add(new BlockInfo(0xA3F00000, 0xA3000027, "RDRAM Registers",".rdreg"));
			add(new BlockInfo(0xA4000000, 0xA4001FFF, "SP Registers",".spreg"));
			add(new BlockInfo(0xA4100000, 0xA410001F, "DP Command Registers",".dpcreg"));
			add(new BlockInfo(0xA4200000, 0xA42FFFFF, "DP Span Registers",".dpsreg"));
			add(new BlockInfo(0xA4300000, 0xA430000f, "MIPS Interface (MI) Registers",".mireg"));
			add(new BlockInfo(0xA4400000, 0xA4400037, "Video Interface (VI) Registers",".vireg"));
			add(new BlockInfo(0xA4500000, 0xA450001f, "Audio Interface (AI) Registers",".aireg"));
			add(new BlockInfo(0xA4600000, 0xA4600033, "Peripheral Interface (PI) Registers",".pireg"));
			add(new BlockInfo(0xA4700000, 0xA470001f, "RDRAM Interface (RI) Registers",".rireg"));
			add(new BlockInfo(0xA4800000, 0xA48000FF, "Serial Interface (SI) Registers",".sireg"));
			add(new BlockInfo(0x1FC00000, 0x1FC007BF, "PIF Boot ROM",".pifrom"));
			add(new BlockInfo(0x1FC007C0, 0x1FC007FF, "PIF RAM",".pifram"));
			add(new BlockInfo(0x80000000, 0x800003FF, "Interrupt Vector Table",".ivt"));
		}
	};
	
	
	@Override
	public String getName() {
		return "N64 Loader by Warranty Voider";
	}

	@Override
	public Collection<LoadSpec> findSupportedLoadSpecs(ByteProvider provider) throws IOException {
		List<LoadSpec> loadSpecs = new ArrayList<>();
		Log.info("N64 Loader: Checking Signature" );
		BinaryReader br = new BinaryReader(provider, false);
		int header = br.readInt(0);
		boolean valid = false;
		switch(header)
		{
			case 0x80371240:
				Log.info( "N64 Loader: Found matching header for big endian" );
				valid = true;
				break;
			case 0x37804012:
				Log.info( "N64 Loader: Found matching header for mixed endian" );
				valid = true;
				break;
			case 0x40123780:
				Log.info( "N64 Loader: Found matching header for little endian" );
				valid = true;
				break;
			default:
				Log.info(String.format("N64 Loader: Found unknown header 0x%08X", header));
				break;
		}
		if(valid)
			loadSpecs.add(new LoadSpec(this, 0, new LanguageCompilerSpecPair("MIPS:BE:32:default", "default"), true));
		return loadSpecs;
	}

	@Override
	protected void load(ByteProvider provider, LoadSpec loadSpec, List<Option> options,
			Program program, TaskMonitor monitor, MessageLog log)
			throws CancelledException, IOException {
			Log.info("N64 Loader: Checking Endianess" );
			BinaryReader br = new BinaryReader(provider, false);
			int header = br.readInt(0);
			byte[] buffROM = provider.getInputStream(0).readAllBytes();
			switch(header)
			{
				case 0x80371240:						
					break;
				case 0x37804012:
					Log.info( "N64 Loader: Fixing mixed endian" );
					MixedSwap(buffROM);
					break;
				case 0x40123780:
					Log.info( "N64 Loader: Fixing little endian" );
					LittleSwap(buffROM);
					break;
			}

			ByteArrayProvider bapROM = new ByteArrayProvider(buffROM);
			Log.info("N64 Loader: Loading header");
			N64Header h = new N64Header(buffROM);
			
			for(BlockInfo bli : initSections)
			{
				long size = (bli.end + 1) - bli.start;
				monitor.setMessage("N64 Loader: Creating segment " + bli.name);
				Log.info("N64 Loader: Creating segment " + bli.name);
				ByteArrayProvider bapBlock = new ByteArrayProvider(new byte[(int)size]);
				if(bli.desc.equals(".pifrom"))
				{
					byte[] data = this.getClass().getClassLoader().getResourceAsStream("pifdata.bin").readAllBytes();
					bapBlock.close();
					bapBlock = new ByteArrayProvider(data);
				}
				MakeBlock(program, bli.desc, bli.name, bli.start, bapBlock.getInputStream(0), (int)size, "111", null, log, monitor);
				bapBlock.close();
			}

			Log.info("N64 Loader: Creating segment ROM");
			Structure header_struct = N64Header.getDataStructure();
			MakeBlock(program, ".rom", "ROM image", 0xB0000000, bapROM.getInputStream(0), (int)bapROM.length(), "100", header_struct, log, monitor);

			Log.info("N64 Loader: Creating segment BOOT");
			MakeBlock(program, ".boot", "ROM bootloader", 0xA4000040, bapROM.getInputStream(0x40),  0xFC0, "111", null, log, monitor);

			Log.info("N64 Loader: Creating segment RAM");
			MakeBlock(program, ".ram", "RAM content", h.loadAddress, bapROM.getInputStream(0x1000),  buffROM.length - 0x1000, "111", null, log, monitor);
			
			bapROM.close();
			
			if(!((String)options.get(0).getValue()).isEmpty())
				ScanPatterns(buffROM, h.loadAddress, (String)options.get(0).getValue(), program, monitor);
			
			try
			{
				Address addr = MakeAddress(0x1FC00000L);
				if(addr != null)
				{
					program.getSymbolTable().addExternalEntryPoint(addr);
				    program.getSymbolTable().createLabel(addr, "pifMain", SourceType.ANALYSIS);
				}
				addr = MakeAddress(0xA4000040L);
				if(addr != null)
				{
					program.getSymbolTable().addExternalEntryPoint(addr);
				    program.getSymbolTable().createLabel(addr, "bootMain", SourceType.ANALYSIS);
				}
				addr = MakeAddress(h.loadAddress);
				if(addr != null)
				{
					program.getSymbolTable().addExternalEntryPoint(addr);
				    program.getSymbolTable().createLabel(addr, "romMain", SourceType.ANALYSIS);
				}
			}catch(Exception ex) {}
			
			Log.info("N64 Loader: Done Loading");
	}
	
	public void MakeBlock(Program program, String name, String desc, long address, InputStream s, int size, String flags, Structure struc, MessageLog log, TaskMonitor monitor)
	{
		try
		{
			byte[] bf = flags.getBytes();
			Address addr = program.getAddressFactory().getDefaultAddressSpace().getAddress(address);
			MemoryBlock block = MemoryBlockUtils.createInitializedBlock(program, false, name, addr, s, size, desc, null, bf[0] == '1', bf[1] == '1', bf[2] == '1', log, monitor);
			blocks.add(block);
			if(struc != null)
				DataUtilities.createData(program, block.getStart(), struc, -1, false, ClearDataMode.CLEAR_ALL_UNDEFINED_CONFLICT_DATA);
		}
		catch (Exception e) {
			Msg.error(this, ExceptionUtils.getStackTrace(e));
		}
	}
	
	public Address MakeAddress(long address)
	{
	    for(MemoryBlock block : blocks)
	    {
	        if(address >= block.getStart().getAddressableWordOffset() &&
	           address <= block.getEnd().getAddressableWordOffset())
	        {
	            Address addr = block.getStart();
	            return addr.add(address - addr.getAddressableWordOffset());            
	        }
	    }
	    return null;
	}
	
	public void MixedSwap(byte[] buff)
	{
		for(int i = 0; i < buff.length; i += 4)
		{
			Swap(buff, i + 0, i + 1);
			Swap(buff, i + 2, i + 3);
		}
	}
	
	public void LittleSwap(byte[] buff)
	{
		for(int i = 0; i < buff.length; i += 4)
		{
			Swap(buff, i + 0, i + 3);
			Swap(buff, i + 1, i + 2);
		}
	}
	
	public void Swap(byte[] buff, int a, int b)
	{
		byte t = buff[a];
		buff[a] = buff[b];
		buff[b] = t;
	}
	
	public void ScanPatterns(byte[] rom, long loadAddress, String sigPath, Program program, TaskMonitor monitor)
	{
		try
		{
			Log.info("N64 Loader: Loading patterns");
			ArrayList<SigPattern> patterns = new ArrayList<SigPattern>();
			List<String> lines = Files.readAllLines(Paths.get(sigPath));
			int maxPatLen = 32;
			for(String line : lines)
			{
				String[] parts = line.split(" ");
				if(parts.length != 2)
					continue;
				SigPattern pat = new SigPattern(parts[0], parts[1]);
				if(pat.pattern.length > maxPatLen)
					maxPatLen = pat.pattern.length;
				patterns.add(pat);
			}			
			Log.info("N64 Loader: Scanning for patterns (" + patterns.size() + ")...");
			monitor.initialize(rom.length - maxPatLen);
			monitor.setMessage("Scanning for patterns (" + patterns.size() + ")...");
			for(int i = 0; i < rom.length - maxPatLen; i += 4)
			{				
				if((i % 0x1000) == 0)
					monitor.setProgress(i);
				for(int j = 0; j < patterns.size(); j++)
				{
					SigPattern sig = patterns.get(j);
					if(sig.Match(rom, i))
					{
						long address = loadAddress + i - 0x1000;
						Address addr = MakeAddress(address);                       
						if(addr != null)
						{
						    SymbolUtilities.createPreferredLabelOrFunctionSymbol(program, addr, null, sig.name, SourceType.ANALYSIS);
						    Log.info("N64 Loader: Found Symbol at " + String.format("0x%08X", address) + " Name=" + sig.name);
						}
						break;
					}
				}
			}
		} catch (IOException | InvalidInputException e) 
		{
			Msg.error(this, e);
		}
	}

	@Override
	public List<Option> getDefaultOptions(ByteProvider provider, LoadSpec loadSpec,
			DomainObject domainObject, boolean isLoadIntoProgram) {
		List<Option> list = new ArrayList<Option>();
		list.add(new Option("Signature file", ""));
		return list;
	}

	@Override
	public String validateOptions(ByteProvider provider, LoadSpec loadSpec, List<Option> options, Program program) {
		return super.validateOptions(provider, loadSpec, options, program);
	}
}
