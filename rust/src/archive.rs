//! archive — tar member safety walker used BEFORE any extraction (spec §33).
//!
//! Parses ustar 512-byte headers and rejects: absolute paths, `..`
//! traversal, host-shaped symlink targets, device/fifo members and entries
//! with out-of-range sizes. Extraction itself stays on the platform side
//! (Kotlin SafeExtractor / guest tar) — this module is the validator.

use crate::security::{check_member_path, symlink_stays_inside, PathVerdict};
use crate::Status;

pub const BLOCK: usize = 512;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Member {
    pub path: String,
    pub size: u64,
    pub kind: u8,
    pub link_target: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArchiveVerdict {
    Safe,
    Unsafe,
    Malformed,
}

fn parse_octal(bytes: &[u8]) -> Option<u64> {
    let text = String::from_utf8_lossy(bytes);
    let trimmed = text.trim_end_matches('\0').trim();
    if trimmed.is_empty() {
        return Some(0);
    }
    u64::from_str_radix(trimmed, 8).ok()
}

/// Extract a NUL-terminated / space-padded header string field.
fn field(block: &[u8], start: usize, len: usize) -> String {
    let slice = &block[start..(start + len)];
    let end = slice.iter().position(|&b| b == 0).unwrap_or(slice.len());
    String::from_utf8_lossy(&slice[..end]).to_string()
}

/// Walk one tar stream and evaluate every member against the security policy.
pub fn validate_tar(data: &[u8]) -> Result<Vec<Member>, Status> {
    let mut members = Vec::new();
    let mut offset = 0usize;
    let mut pending_long_name: Option<String> = None;

    while offset + BLOCK <= data.len() {
        let block = &data[offset..offset + BLOCK];
        offset += BLOCK;
        if block.iter().all(|&b| b == 0) {
            break;
        }
        let magic_ok = &block[257..262] == b"ustar" || block[257] == 0;
        if !magic_ok {
            return Err(Status::InvalidArgument);
        }
        let type_flag = block[156];
        let size = parse_octal(&block[124..136]).ok_or(Status::InvalidArgument)?;
        let name = pending_long_name.take().unwrap_or_else(|| field(block, 0, 100));

        match type_flag {
            b'L' => {
                // GNU long name payload becomes the next member's path.
                let payload_end = (offset + size as usize).min(data.len());
                let long = String::from_utf8_lossy(&data[offset..payload_end]);
                pending_long_name = Some(long.trim_end_matches('\0').to_string());
                offset += ((size as usize) + BLOCK - 1) / BLOCK * BLOCK;
                continue;
            }
            0x30 | 0x00 | b'x' | b'g' => { /* regular file / pax header */ }
            0x31 | 0x32 => { /* hardlink / symlink: target checked below */ }
            0x33..=0x37 => return Err(Status::UnsafeArchive), // device/fifo members
            _ => return Err(Status::InvalidArgument),
        }

        let link_target = if type_flag == 0x32 || type_flag == 0x31 {
            let target = field(block, 157, 100);
            if target.is_empty() {
                return Err(Status::UnsafeArchive);
            }
            Some(target)
        } else {
            None
        };

        // Path policy: no traversal, no absolute members.
        if check_member_path(&name) != PathVerdict::Safe {
            return Err(Status::UnsafeArchive);
        }
        // Symlink targets must stay inside the member's directory.
        if let Some(target) = &link_target {
            let member_dir = match name.rfind('/') {
                Some(index) => &name[..index],
                None => "",
            };
            if symlink_stays_inside(member_dir, target) != PathVerdict::Safe {
                return Err(Status::UnsafeArchive);
            }
        }
        let data_end = offset + size as usize;
        if data_end > data.len() {
            return Err(Status::InvalidArgument);
        }
        members.push(Member { path: name, size, kind: type_flag, link_target });
        offset += ((size as usize) + BLOCK - 1) / BLOCK * BLOCK;
    }
    Ok(members)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(name: &str, size: u64, type_flag: u8, link: &str) -> Vec<u8> {
        let mut block = vec![0u8; 512];
        block[..name.len()].copy_from_slice(name.as_bytes());
        block[100..108].copy_from_slice(format!("{:07o}", 0o644).as_bytes());
        block[124..136].copy_from_slice(format!("{:011o}", size).as_bytes());
        block[156] = type_flag;
        block[157..157 + link.len()].copy_from_slice(link.as_bytes());
        block[257..262].copy_from_slice(b"ustar");
        block[263..265].copy_from_slice(b"00");
        block
    }

    #[test]
    fn accepts_a_safe_file() {
        let mut tar = header("usr/bin/tree", 5, b'0', "");
        tar.extend_from_slice(b"hello");
        tar.extend_from_slice(&vec![0u8; 507]);
        tar.extend_from_slice(&vec![0u8; 1024]); // end blocks
        let members = validate_tar(&tar).expect("safe");
        assert_eq!(members.len(), 1);
        assert_eq!(members[0].path, "usr/bin/tree");
    }

    #[test]
    fn rejects_traversal_and_devices() {
        let mut tar = header("../evil", 0, b'0', "");
        tar.extend_from_slice(&vec![0u8; 1536]);
        assert_eq!(validate_tar(&tar).unwrap_err(), Status::UnsafeArchive);

        let mut tar2 = header("/dev/null", 0, b'2', "");
        tar2.extend_from_slice(&vec![0u8; 1536]);
        assert!(validate_tar(&tar2).is_err());
    }

    #[test]
    fn rejects_escaping_symlink() {
        let mut tar = header("usr/bin/bad", 0, 0x32, "../../etc/shadow");
        tar.extend_from_slice(&vec![0u8; 1536]);
        assert_eq!(validate_tar(&tar).unwrap_err(), Status::UnsafeArchive);
    }
}
