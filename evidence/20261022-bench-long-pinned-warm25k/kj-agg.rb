dir = ARGV[0]
rows = Hash.new { |h,k| h[k] = Hash.new { |h2,k2| h2[k2] = [] } }
Dir["#{dir}/*.log"].sort.each do |f|
  b = File.basename(f); rt = b[/^(patched|stock|mri)/] or next
  rnd = b[/-(\d+)\.log/, 1].to_i
  File.foreach(f) { |l| rows[$1][rt] << [$2.to_f, $3.to_f, $4.to_f, rnd] if l =~ /^(\S+)\s+msg\/s=([\d.]+) p50=([\d.]+) p99=([\d.]+)/ }
end
med = ->(a) { s = a.sort; n = s.size; n.odd? ? s[n/2] : (s[n/2-1] + s[n/2]) / 2.0 }
puts "size   patched med (min-max)     JNR med (min-max)        MRI    ratio  per-round med (min-max)  p50us pat/JNR  p99us pat/JNR"
%w[100B 1KB 10KB 100KB].each do |sz|
  r = rows[sz]; pa = r["patched"].sort_by { _1[3] }; st = r["stock"].sort_by { _1[3] }
  ra = pa.zip(st).map { |a, b| a[0] / b[0] }
  printf("%-6s %7.0f (%6.0f-%6.0f)  %7.0f (%6.0f-%6.0f)  %6.0f  %5.2fx  %4.2f (%4.2f-%4.2f)       %5.1f/%5.1f   %5.1f/%5.1f\n", sz,
    med[pa.map(&:first)], *pa.map(&:first).minmax, med[st.map(&:first)], *st.map(&:first).minmax, med[r["mri"].map(&:first)],
    med[pa.map(&:first)] / med[st.map(&:first)], med[ra], *ra.minmax,
    med[pa.map { _1[1] }]*1e3, med[st.map { _1[1] }]*1e3, med[pa.map { _1[2] }]*1e3, med[st.map { _1[2] }]*1e3)
end
puts "runs: " + %w[patched stock mri].map { |rt| "#{rt}=#{rows["1KB"][rt].size}" }.join(" ")
