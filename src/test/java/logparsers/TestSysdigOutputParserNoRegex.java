package logparsers;

import org.junit.jupiter.api.Test;
import pagerank.MetaConfig;

import java.io.IOException;

class TestSysdigOutputParserNoRegex {

	@Test
	void TestParsing1() throws IOException {
		SysdigOutputParserNoRegex parser = new SysdigOutputParserNoRegex("input/attacks_bad/command_injection_step3.txt", MetaConfig.localIP);
		parser.getEntities();
	}

	@Test
	void TestParsing2() throws IOException {
		SysdigOutputParserNoRegex parser = new SysdigOutputParserNoRegex("input/attacks_bad/command_injection_step5.txt", MetaConfig.localIP);
		parser.getEntities();
	}

	@Test
	void TestParsing3() throws IOException {
		SysdigOutputParserNoRegex parser = new SysdigOutputParserNoRegex("input/attacks_bad/data_leakage.txt", MetaConfig.localIP);
		parser.getEntities();
	}

	@Test
	void TestParsing4() throws IOException {
		SysdigOutputParserNoRegex parser = new SysdigOutputParserNoRegex("input/attacks_bad/password_crack_step1.txt",MetaConfig.localIP);
		parser.getEntities();
	}

	@Test
	void TestParsing5() throws IOException {
		SysdigOutputParserNoRegex parser = new SysdigOutputParserNoRegex("input/attacks_bad/password_crack_step4.txt",MetaConfig.localIP);
		parser.getEntities();
	}

	@Test
	void TestParsing6() throws IOException {
		SysdigOutputParserNoRegex parser = new SysdigOutputParserNoRegex("input/attacks_bad/password_crack_step5.txt",MetaConfig.localIP);
		parser.getEntities();
	}

	@Test
	void TestParsing7() throws IOException {
		SysdigOutputParserNoRegex parser = new SysdigOutputParserNoRegex("input/attacks_bad/pentration_step1.txt",MetaConfig.localIP);
		parser.getEntities();
	}

	@Test
	void TestParsing8() throws IOException {
		SysdigOutputParserNoRegex parser = new SysdigOutputParserNoRegex("input/attacks_bad/pentration_step3.txt",MetaConfig.localIP);
		parser.getEntities();
	}

	@Test
	void TestParsing9() throws IOException {
		SysdigOutputParserNoRegex parser = new SysdigOutputParserNoRegex("input/attacks_bad/vpnfilter_step1.txt",MetaConfig.localIP);
		parser.getEntities();
	}

	@Test
	void TestParsing10() throws IOException {
		SysdigOutputParserNoRegex parser = new SysdigOutputParserNoRegex("input/attacks_bad/vpnfilter_step5.txt",MetaConfig.localIP);
		parser.getEntities();
	}
}