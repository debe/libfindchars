package org.knownhosts.libfindchars.generator;

public class Target {
	
	private String packageName;
	private String directory;

	public Target withPackageName(String packageName){
		this.packageName = packageName;
		return this;
	}

	public Target withDirectory(String directory){
		this.directory = directory;
		return this;
	}

	public String getPackageName() {
		return packageName;
	}

	public String getDirectory() {
		return directory;
	}
	
	
}
