const regex =
  /Array\.isArray\(context\.result\)\s*{\s*context\.result = await Promise\.all\(context\.result\.map\(injectToken\)\);/g;
console.log(regex);
