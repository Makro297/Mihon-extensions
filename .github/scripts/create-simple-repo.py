#!/usr/bin/env python3
import json
import os
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile

# Regex patterns for APK metadata
PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
IS_NSFW_REGEX = re.compile(r"'tachiyomi.extension.nsfw' value='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-320:'([^']+)'", re.MULTILINE)
LANGUAGE_REGEX = re.compile(r"tachiyomi-([^.]+)")

def get_android_build_tools():
    """Find Android build tools directory"""
    android_home = os.environ.get("ANDROID_HOME")
    if not android_home:
        # Try common locations
        possible_paths = [
            "/usr/local/android-sdk",
            "/opt/android-sdk",
            os.path.expanduser("~/Android/Sdk")
        ]
        for path in possible_paths:
            if os.path.exists(path):
                android_home = path
                break
    
    if android_home:
        build_tools_dir = Path(android_home) / "build-tools"
        if build_tools_dir.exists():
            # Get the latest build tools version
            versions = [d for d in build_tools_dir.iterdir() if d.is_dir()]
            if versions:
                return max(versions)
    
    return None

def create_repository():
    repo_dir = Path("repo")
    repo_apk_dir = repo_dir / "apk"
    repo_icon_dir = repo_dir / "icon"
    
    # Create directories
    repo_icon_dir.mkdir(parents=True, exist_ok=True)
    
    # Find Android build tools
    build_tools = get_android_build_tools()
    if not build_tools:
        print("Warning: Android build tools not found. Using fallback method.")
        create_simple_index()
        return
    
    aapt_path = build_tools / "aapt"
    if not aapt_path.exists():
        print("Warning: aapt not found. Using fallback method.")
        create_simple_index()
        return
    
    index_data = []
    
    # Process APK files
    for apk_file in repo_apk_dir.glob("*.apk"):
        try:
            # Get APK metadata using aapt
            result = subprocess.run([
                str(aapt_path), "dump", "--include-meta-data", "badging", str(apk_file)
            ], capture_output=True, text=True, check=True)
            
            badging_output = result.stdout
            
            # Extract metadata
            package_info = next((line for line in badging_output.splitlines() if line.startswith("package: ")), "")
            if not package_info:
                continue
                
            package_name = PACKAGE_NAME_REGEX.search(package_info)
            version_code = VERSION_CODE_REGEX.search(package_info)
            version_name = VERSION_NAME_REGEX.search(package_info)
            app_label = APPLICATION_LABEL_REGEX.search(badging_output)
            app_icon = APPLICATION_ICON_320_REGEX.search(badging_output)
            is_nsfw = IS_NSFW_REGEX.search(badging_output)
            language_match = LANGUAGE_REGEX.search(apk_file.name)
            
            if not all([package_name, version_code, version_name, app_label]):
                continue
            
            # Extract icon if available
            if app_icon:
                try:
                    with ZipFile(apk_file) as z:
                        with z.open(app_icon.group(1)) as icon_file:
                            icon_path = repo_icon_dir / f"{package_name.group(1)}.png"
                            with open(icon_path, "wb") as f:
                                f.write(icon_file.read())
                except:
                    pass  # Icon extraction failed, continue without it
            
            # Create extension entry
            extension_data = {
                "name": app_label.group(1),
                "pkg": package_name.group(1),
                "apk": apk_file.name,
                "lang": language_match.group(1) if language_match else "vi",
                "code": int(version_code.group(1)),
                "version": version_name.group(1),
                "nsfw": int(is_nsfw.group(1)) if is_nsfw else 0,
                "sources": []
            }
            
            # Add source information (simplified for Team Lanh Lung)
            if "teamlanhlung" in apk_file.name.lower():
                extension_data["sources"].append({
                    "name": "Team Lạnh Lùng",
                    "lang": "vi",
                    "id": "teamlanhlung",
                    "baseUrl": "https://teamlanhlungday.me"
                })
            
            index_data.append(extension_data)
            
        except subprocess.CalledProcessError as e:
            print(f"Error processing {apk_file}: {e}")
            continue
        except Exception as e:
            print(f"Unexpected error processing {apk_file}: {e}")
            continue
    
    # Write index.min.json
    with open(repo_dir / "index.min.json", "w", encoding="utf-8") as f:
        json.dump(index_data, f, ensure_ascii=False, separators=(",", ":"))
    
    print(f"Created repository with {len(index_data)} extensions")

def create_simple_index():
    """Fallback method when aapt is not available"""
    repo_dir = Path("repo")
    repo_apk_dir = repo_dir / "apk"
    
    index_data = []
    
    for apk_file in repo_apk_dir.glob("*.apk"):
        # Simple metadata extraction based on filename
        if "teamlanhlung" in apk_file.name.lower():
            # Extract version from filename if possible
            version_match = re.search(r"v(\d+\.\d+\.\d+)", apk_file.name)
            version = version_match.group(1) if version_match else "1.4.26"
            
            extension_data = {
                "name": "Team Lạnh Lùng",
                "pkg": "eu.kanade.tachiyomi.extension.vi.teamlanhlung",
                "apk": apk_file.name,
                "lang": "vi",
                "code": 26,
                "version": version,
                "nsfw": 1,
                "sources": [{
                    "name": "Team Lạnh Lùng",
                    "lang": "vi",
                    "id": "teamlanhlung",
                    "baseUrl": "https://teamlanhlungday.me"
                }]
            }
            index_data.append(extension_data)
    
    # Write index.min.json
    with open(repo_dir / "index.min.json", "w", encoding="utf-8") as f:
        json.dump(index_data, f, ensure_ascii=False, separators=(",", ":"))
    
    print(f"Created simple repository with {len(index_data)} extensions")

if __name__ == "__main__":
    create_repository()