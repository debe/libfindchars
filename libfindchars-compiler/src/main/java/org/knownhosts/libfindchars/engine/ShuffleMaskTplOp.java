package org.knownhosts.libfindchars.engine;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import org.knownhosts.libfindchars.api.FindMask;

public class ShuffleMaskTplOp implements TplOp {

	private final List<FindMask> findMasks;

	public List<FindMask> getFindMasks() {
		return findMasks;
	}

	@Override
	public String getFieldsTpl() {
		return Paths.get("templates","shufflemaskop","fields.vm").toString();
	}

	@Override
	public String getInitTpl() {
		
		return Paths.get("templates","shufflemaskop","initialize.vm").toString();
	}

	@Override
	public String getInlineTpl() {
		return Paths.get("templates","shufflemaskop","inline.vm").toString();
	}

	public ShuffleMaskTplOp( List<FindMask> findMasks) {
		super();
		this.findMasks = Collections.unmodifiableList(findMasks);
	}
	
}
