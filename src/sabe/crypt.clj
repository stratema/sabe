(ns sabe.crypt
  (:require [clojure.data.codec.base64 :as base64])
  (:import java.nio.charset.Charset
           java.security.SecureRandom
           [javax.crypto Cipher KeyGenerator]
           [javax.crypto.spec IvParameterSpec SecretKeySpec]))

(defn utf8-bytes ^bytes
  [value]
  (.getBytes ^String value ^Charset (Charset/forName "UTF-8")))

(defn byte-array?
  "Testsif a value is a byte[]"
  [object]
  (= (Class/forName "[B") (type object)))

(defn get-iv-bytes
  "Initialisesand returns a random iv vector byte[]"
  []
  (let [sr       (SecureRandom/getInstance "SHA1PRNG")
        iv-bytes (byte-array 16)]
    (.nextBytes sr iv-bytes)
    iv-bytes))

(defn get-raw-key
  "Returns a AES SecretKey encoded as a byte[]"
  [seed]
  (let [keygen (KeyGenerator/getInstance "AES")
        sr     (SecureRandom/getInstance "SHA1PRNG")]
    (.setSeed sr (utf8-bytes seed))
    (.init keygen 128 sr)
    (.. keygen generateKey getEncoded)))

(defn get-cipher
  "Returns an AES/CBC/PKCS5Padding Cipher which can be used to encrypt
  of decrypt a byte[], depending on the mode of the Cipher."
  ^Cipher
  [mode seed iv-bytes]
  (let [key-spec (SecretKeySpec. (get-raw-key seed) "AES")
        iv-spec  (IvParameterSpec. iv-bytes)]
    (doto (Cipher/getInstance "AES/CBC/PKCS5Padding")
      (.init (int mode) key-spec iv-spec))))

(defn encrypt
  "Symmetrically encrypt value with `key`, such that it can be
  decrypted later with `decrypt`"
  [value key]
  (let [value-bytes (if (string? value) (utf8-bytes value) value)
        iv-bytes    (get-iv-bytes)
        cipher      (get-cipher Cipher/ENCRYPT_MODE key iv-bytes)]
    (into-array Byte/TYPE (concat iv-bytes
                                  (.doFinal cipher value-bytes)))))

(defn decrypt
  "Decrypt a value which has been encrypted via `encrypt`. Returns a byte[].
  This first 16 bytes of the input value is the initialisation vector
  to use when decrypting. The remainder is the encrypted data."
  [value key]
  (let [[iv-bytes encrypted-data] (split-at 16 value)
        iv-bytes                  (into-array Byte/TYPE iv-bytes)
        encrypted-data            (into-array Byte/TYPE encrypted-data)
        cipher                    (get-cipher Cipher/DECRYPT_MODE key iv-bytes)]
    (.doFinal cipher encrypted-data)))

(defn encrypt-as-base64
  "Symmetrically encrypts value with key, returning a base64 encoded
  string, such that it can be decrypted later with `decrypt-from-base64`"
  ([value key]
   (encrypt-as-base64 value key (Charset/defaultCharset)))
  ([value key ^Charset charset]
   (String. ^bytes (base64/encode (encrypt value key)) charset)))

(defn decrypt-from-base64
  "Decrypts a value which has been encrypted via `encrypt-as-base64` and
  attempts to construct the decrypted value into a String."
  ([value key]
   (decrypt-from-base64 value key (Charset/defaultCharset)))
  ([^String value key ^Charset charset]
   (String. ^bytes (decrypt (base64/decode (.getBytes value)) key) charset)))
